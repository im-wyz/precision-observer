package com.nnu.rasterapi.service;

import com.nnu.rasterapi.dto.TaskCreateRequest;
import com.nnu.rasterapi.dto.TaskResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容旧的 /api/agent/tasks 入口。
 *
 * 主链路已经统一为 /api/tasks：Spring 先落 PostgreSQL 任务库，再转发 FastAPI /analyze，
 * 后续由 Redis Pub/Sub + STOMP 推送进度。这里不再单独直连 FastAPI，避免绕过任务库与 WebSocket。
 */
@Service
public class AgentTaskService {

    private final TaskService taskService;

    public AgentTaskService(TaskService taskService) {
        this.taskService = taskService;
    }

    public Map<String, Object> submitTask(String message, Map<String, Object> context) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        TaskCreateRequest request = new TaskCreateRequest(
                message.trim(),
                readRegionCoords(context),
                readDate(context, "startDate", "start_date", LocalDate.now().minusMonths(1)),
                readDate(context, "endDate", "end_date", LocalDate.now())
        );
        TaskResponse created = taskService.createTask(request);
        Map<String, Object> snapshot = taskService.getTaskSnapshot(created.id());
        snapshot.putIfAbsent("id", created.id());
        return snapshot;
    }

    public Map<String, Object> getTask(String taskId) {
        return taskService.getTaskSnapshot(taskId);
    }

    private static List<Object> readRegionCoords(Map<String, Object> context) {
        Object raw = readAny(context, "regionCoords", "region_coords");
        if (raw instanceof List<?> list) {
            return list.stream().map(x -> (Object) x).toList();
        }
        return Collections.emptyList();
    }

    private static String readDate(Map<String, Object> context, String camelKey, String snakeKey, LocalDate fallback) {
        Object raw = readAny(context, camelKey, snakeKey);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return fallback.toString();
    }

    private static Object readAny(Map<String, Object> context, String camelKey, String snakeKey) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(context);
        Object nested = normalized.get("context");
        if (nested instanceof Map<?, ?> nestedMap) {
            for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    normalized.putIfAbsent(key, entry.getValue());
                }
            }
        }
        if (normalized.containsKey(camelKey)) {
            return normalized.get(camelKey);
        }
        return normalized.get(snakeKey);
    }
}
