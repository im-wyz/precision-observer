package com.nnu.rasterapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nnu.rasterapi.dto.TaskCreateRequest;
import com.nnu.rasterapi.dto.TaskResponse;
import com.nnu.rasterapi.entity.Task;
import com.nnu.rasterapi.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    private final TaskRepository taskRepository;
    private final WebClient agentWebClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String redisKeyPrefix;
    private final String redisPubsubChannelPrefix;
    private final Executor agentTaskExecutor;

    public TaskService(
            TaskRepository taskRepository,
            WebClient agentWebClient,
            SimpMessagingTemplate messagingTemplate,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            @Value("${agent.redis-key-prefix:agent:task:}") String redisKeyPrefix,
            @Value("${agent.redis-pubsub-channel-prefix:task:}") String redisPubsubChannelPrefix
    ) {
        this.taskRepository = taskRepository;
        this.agentWebClient = agentWebClient;
        this.messagingTemplate = messagingTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
        this.redisKeyPrefix = redisKeyPrefix == null ? "agent:task:" : redisKeyPrefix;
        this.redisPubsubChannelPrefix = redisPubsubChannelPrefix == null ? "task:" : redisPubsubChannelPrefix;
    }

    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        validateCreateRequest(request);
        String taskId = UUID.randomUUID().toString();
        Task task = new Task();
        task.setId(taskId);
        task.setMessage(request.message().trim());
        task.setRegionCoords(serializeRegionCoords(request.regionCoords()));
        task.setStartDate(LocalDate.parse(request.startDate()));
        task.setEndDate(LocalDate.parse(request.endDate()));
        task.setStatus("queued");
        taskRepository.save(task);

        writeInitialRedisSnapshot(taskId, request);
        agentTaskExecutor.execute(() -> submitToAgent(taskId, request));
        return toResponse(task, "api", "任务已创建，正在连接智能体…");
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在：" + taskId));
        Map<String, Object> snapshot = readRedisSnapshot(taskId);
        String currentNode = textOrNull(snapshot, "current_node");
        String lastMessage = textOrNull(snapshot, "last_message");
        return toResponse(task, currentNode, lastMessage);
    }

    /**
     * 返回 Redis 中的完整任务 JSON（与 Python publish / STOMP 推送体一致），供前端轮询兜底。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getTaskSnapshot(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在：" + taskId));
        Map<String, Object> snapshot = readRedisSnapshot(taskId);
        if (!snapshot.isEmpty()) {
            return snapshot;
        }
        // Redis 尚无键时返回占位（避免轮询 404）；常见于 agent-api 未启动或 Redis 不可达
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("task_id", taskId);
        stub.put("status", task.getStatus() != null ? task.getStatus() : "queued");
        stub.put("current_node", "api");
        stub.put("last_message", "等待 agent-api 写入进度（请确认 Redis 与 :8001 服务）");
        stub.put(
                "progress",
                List.of(Map.of("node", "api", "message", "等待 agent-api 写入进度（请确认 Redis 与 :8001 服务）"))
        );
        return stub;
    }

    private void writeInitialRedisSnapshot(String taskId, TaskCreateRequest request) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("task_id", taskId);
        doc.put("status", "queued");
        doc.put("user_message", request.message().trim());
        doc.put("region_coords", request.regionCoords() != null ? request.regionCoords() : Collections.emptyList());
        doc.put("start_date", request.startDate());
        doc.put("end_date", request.endDate());
        if (request.compareStartDate() != null && !request.compareStartDate().isBlank()) {
            doc.put("compare_start_date", request.compareStartDate());
        }
        if (request.compareEndDate() != null && !request.compareEndDate().isBlank()) {
            doc.put("compare_end_date", request.compareEndDate());
        }
        doc.put("current_node", "api");
        doc.put("last_message", "任务已创建，正在连接智能体…");
        doc.put(
                "progress",
                List.of(Map.of("node", "api", "message", "任务已创建，正在连接智能体…"))
        );
        try {
            String json = objectMapper.writeValueAsString(doc);
            redis.opsForValue().set(redisKeyPrefix + taskId, json, Duration.ofSeconds(86400));
            redis.convertAndSend(redisPubsubChannelPrefix + taskId, json);
            pushWebSocket(taskId, json);
        } catch (Exception e) {
            log.warn("写入 Redis 初始快照失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Redis 订阅回调：更新数据库、推送 WebSocket。
     */
    @Transactional
    public void onRedisProgress(String taskId, String jsonBody) {
        pushWebSocket(taskId, jsonBody);
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            taskRepository.findById(taskId).ifPresent(task -> applyJsonToTask(task, root));
        } catch (Exception e) {
            log.warn("解析 Redis 任务消息失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public void pushWebSocket(String taskId, Object payload) {
        String destination = "/topic/task/" + taskId;
        messagingTemplate.convertAndSend(destination, payload);
    }

    void submitToAgent(String taskId, TaskCreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("message", request.message().trim());
        body.put("region_coords", request.regionCoords() != null ? request.regionCoords() : Collections.emptyList());
        body.put("start_date", request.startDate());
        body.put("end_date", request.endDate());
        if (request.compareStartDate() != null && !request.compareStartDate().isBlank()) {
            body.put("compare_start_date", request.compareStartDate());
        }
        if (request.compareEndDate() != null && !request.compareEndDate().isBlank()) {
            body.put("compare_end_date", request.compareEndDate());
        }

        agentWebClient.post()
                .uri("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(ex -> {
                    log.error("调用 agent-api /analyze 失败 taskId={}", taskId, ex);
                    markSubmitFailed(taskId, ex.getMessage());
                    return Mono.empty();
                })
                .subscribe(resp -> {
                    if (resp != null) {
                        markSubmitted(taskId, resp);
                    }
                });
    }

    private void markSubmitted(String taskId, JsonNode resp) {
        taskRepository.findById(taskId).ifPresent(task -> {
            String status = resp.hasNonNull("status") ? resp.get("status").asText() : "submitted";
            task.setStatus(status);
            taskRepository.save(task);
        });
    }

    private void markSubmitFailed(String taskId, String error) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus("submit_failed");
            task.setErrorMessage(abbreviate(error, 2000));
            taskRepository.save(task);
            Map<String, Object> errPayload = Map.of(
                    "task_id", taskId,
                    "status", "submit_failed",
                    "error", task.getErrorMessage()
            );
            try {
                String json = objectMapper.writeValueAsString(errPayload);
                redis.opsForValue().set(redisKeyPrefix + taskId, json, Duration.ofSeconds(86400));
                redis.convertAndSend(redisPubsubChannelPrefix + taskId, json);
                pushWebSocket(taskId, json);
            } catch (Exception e) {
                pushWebSocket(taskId, errPayload);
            }
        });
    }

    private void applyJsonToTask(Task task, JsonNode root) {
        if (root.hasNonNull("status")) {
            task.setStatus(root.get("status").asText());
        }
        if (root.hasNonNull("answer")) {
            task.setAnswer(root.get("answer").asText());
        }
        if (root.hasNonNull("cog_path")) {
            task.setCogPath(root.get("cog_path").asText());
        }
        if (root.hasNonNull("download_url")) {
            task.setDownloadUrl(root.get("download_url").asText());
        }
        if (root.hasNonNull("error")) {
            task.setErrorMessage(root.get("error").asText());
        } else if (root.hasNonNull("error_message")) {
            task.setErrorMessage(root.get("error_message").asText());
        }
        taskRepository.save(task);
    }

    private Map<String, Object> readRedisSnapshot(String taskId) {
        String raw = redis.opsForValue().get(redisKeyPrefix + taskId);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private TaskResponse toResponse(Task task, String currentNode, String lastMessage) {
        List<Object> coords = parseRegionCoords(task.getRegionCoords());
        return TaskResponse.fromEntity(task, coords, currentNode, lastMessage);
    }

    private List<Object> parseRegionCoords(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String serializeRegionCoords(List<Object> regionCoords) {
        if (regionCoords == null || regionCoords.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(regionCoords);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "regionCoords 无法序列化为 JSON");
        }
    }

    private static void validateCreateRequest(TaskCreateRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        if (request.startDate() == null || request.startDate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate 不能为空（YYYY-MM-DD）");
        }
        if (request.endDate() == null || request.endDate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate 不能为空（YYYY-MM-DD）");
        }
    }

    private static String textOrNull(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
