package com.nnu.rasterapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 地物相关推理：自建 Inference 时 Workflow 使用
 * {@code POST /{workspace}/workflows/{workflow_id}}（默认可配 legacy：{@code /infer/workflows/...}），
 * body 为 api_key + inputs；单模型用 {@code POST /infer/{task}}；无 inference-base-url 时回退 Serverless。
 */
@Service
public class RoboflowWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(RoboflowWorkflowService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> INFERENCE_TASKS = Set.of(
            "object_detection", "classification", "instance_segmentation"
    );
    /** serverless.roboflow.com 前常有 Cloudflare；无浏览器式 UA 时易被 403 拦下 */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String inferenceBaseUrl;
    private final String inferenceTask;
    private final String inferenceModelId;
    /** 与 workflow_id 成对出现 */
    private final String inferenceWorkspace;
    private final String inferenceWorkflowId;
    /** Workflow 专用 TiTiler 预览 max_size，宜小以减轻 JSON 体积 */
    private final int workflowPreviewMaxSize;
    /** true 时只用旧路径 /infer/workflows/... */
    private final boolean workflowLegacyPathOnly;
    private final double inferenceConfidence;
    private final double inferenceIouThreshold;
    private final String workflowUrl;
    private final String titilerBaseUrl;
    /** Serverless workflow 等长 POST */
    private final Duration serverlessTimeout;
    /** 自建 GPU Inference 单次 POST */
    private final Duration inferenceTimeout;
    /** TiTiler /cog/preview 单次 GET */
    private final Duration titilerTimeout;
    private final int previewMaxSize;
    private final long maxPreviewBytes;
    /** 503/429 时最多额外重试次数（首次请求不计入） */
    private final int inferenceRetryMax;
    private final long inferenceRetryBaseDelayMs;
    /** Inference 重试总墙上时间（毫秒），防止无限挂起 */
    private final long inferenceMaxWallMs;
    private final long inferenceRetryDelayCapMs;
    /** 单卡 GPU 时并发 POST 易 503；为 true 时仅对 httpClient.send 加 JVM 内互斥（退避在锁外仍生效） */
    private final boolean inferenceSerializeRequests;
    private final Object inferenceHttpLock = new Object();

    public RoboflowWorkflowService(
            @Value("${roboflow.api-key:}") String apiKey,
            @Value("${roboflow.inference-base-url:}") String inferenceBaseUrl,
            @Value("${roboflow.inference-task:object_detection}") String inferenceTask,
            @Value("${roboflow.inference-model-id:}") String inferenceModelId,
            @Value("${roboflow.inference-workspace:}") String inferenceWorkspace,
            @Value("${roboflow.inference-workflow-id:}") String inferenceWorkflowId,
            @Value("${roboflow.workflow-preview-max-size:512}") int workflowPreviewMaxSize,
            @Value("${roboflow.inference-workflow-legacy-path:false}") boolean workflowLegacyPathOnly,
            @Value("${roboflow.inference-confidence:0.4}") double inferenceConfidence,
            @Value("${roboflow.inference-iou-threshold:0.5}") double inferenceIouThreshold,
            @Value("${roboflow.workflow-url:https://serverless.roboflow.com/wyzs-workspace-mwh2e/workflows/1778471606854}") String workflowUrl,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${roboflow.timeout-ms:120000}") long timeoutMs,
            @Value("${roboflow.inference-request-timeout-ms:0}") long inferenceRequestTimeoutMs,
            @Value("${roboflow.titiler-preview-timeout-ms:0}") long titilerPreviewTimeoutMs,
            @Value("${roboflow.inference-max-wall-ms:720000}") long inferenceMaxWallMs,
            @Value("${roboflow.inference-retry-max:6}") int inferenceRetryMax,
            @Value("${roboflow.inference-retry-base-delay-ms:1200}") long inferenceRetryBaseDelayMs,
            @Value("${roboflow.inference-retry-delay-cap-ms:30000}") long inferenceRetryDelayCapMs,
            @Value("${roboflow.inference-serialize-requests:false}") boolean inferenceSerializeRequests,
            @Value("${roboflow.preview-max-size:1536}") int previewMaxSize,
            @Value("${roboflow.max-preview-bytes:16777216}") long maxPreviewBytes
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.inferenceBaseUrl = inferenceBaseUrl == null ? "" : inferenceBaseUrl.replaceAll("/$", "");
        this.inferenceTask = inferenceTask == null ? "object_detection" : inferenceTask.trim();
        this.inferenceModelId = inferenceModelId == null ? "" : inferenceModelId.trim();
        this.inferenceWorkspace = inferenceWorkspace == null ? "" : inferenceWorkspace.trim();
        this.inferenceWorkflowId = inferenceWorkflowId == null ? "" : inferenceWorkflowId.trim();
        this.workflowPreviewMaxSize = Math.max(128, Math.min(2048, workflowPreviewMaxSize));
        this.workflowLegacyPathOnly = workflowLegacyPathOnly;
        this.inferenceConfidence = inferenceConfidence;
        this.inferenceIouThreshold = inferenceIouThreshold;
        this.workflowUrl = workflowUrl == null ? "" : workflowUrl.replaceAll("/$", "");
        this.titilerBaseUrl = titilerBaseUrl == null ? "" : titilerBaseUrl.replaceAll("/$", "");
        long effServerlessMs = Math.max(5000, timeoutMs);
        this.serverlessTimeout = Duration.ofMillis(effServerlessMs);
        long inferReqMs = inferenceRequestTimeoutMs > 0 ? inferenceRequestTimeoutMs : effServerlessMs;
        this.inferenceTimeout = Duration.ofMillis(Math.max(5000, inferReqMs));
        long titilerMs = titilerPreviewTimeoutMs > 0
                ? titilerPreviewTimeoutMs
                : Math.min(120_000L, effServerlessMs);
        this.titilerTimeout = Duration.ofMillis(Math.max(3000, titilerMs));
        this.previewMaxSize = Math.max(256, Math.min(4096, previewMaxSize));
        this.maxPreviewBytes = Math.max(1_000_000, maxPreviewBytes);
        this.inferenceRetryMax = Math.max(0, Math.min(20, inferenceRetryMax));
        this.inferenceRetryBaseDelayMs = Math.max(100, Math.min(60_000, inferenceRetryBaseDelayMs));
        this.inferenceMaxWallMs = Math.max(10_000L, Math.min(3_600_000L, inferenceMaxWallMs));
        this.inferenceRetryDelayCapMs = Math.max(100L, Math.min(120_000L, inferenceRetryDelayCapMs));
        this.inferenceSerializeRequests = inferenceSerializeRequests;
        // HTTP/1.1：避免部分环境下对超大 POST 的 HTTP/2 或解析问题；Inference 为 uvicorn/h11 时常见 400 Invalid HTTP request
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        if (this.inferenceSerializeRequests) {
            log.info("roboflow.inference-serialize-requests=true：Inference HTTP POST 在本 JVM 内串行，减轻单卡 503");
        }
    }

    /**
     * @param modelIdOverride           可选，覆盖 roboflow.inference-model-id / ROBOFLOW_MODEL_ID
     * @param inferenceBaseUrlOverride 可选，覆盖 roboflow.inference-base-url（便于 Spring 与推理容器不在同一配置文件）
     * @param inferenceTaskOverride     可选，覆盖 roboflow.inference-task
     * @param workspaceOverride         可选，覆盖 roboflow.inference-workspace
     * @param workflowIdOverride        可选，覆盖 roboflow.inference-workflow-id
     * @param pixelsPerUnitOverride       可选，传入工作流 pixels_per_unit（米/像素）；null 时不写该字段，由工作流默认处理
     */
    public JsonNode runWorkflow(
            String instruction,
            String cogHttpUrl,
            String modelIdOverride,
            String inferenceBaseUrlOverride,
            String inferenceTaskOverride,
            String workspaceOverride,
            String workflowIdOverride,
            Double pixelsPerUnitOverride
    ) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 Roboflow API Key（请设置环境变量 ROBOFLOW_API_KEY）");
        }
        if (titilerBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "live.titiler.base-url 未配置");
        }
        String ins = instruction == null ? "" : instruction.trim();
        String cog = cogHttpUrl == null ? "" : cogHttpUrl.trim();
        if (ins.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instruction 不能为空");
        }
        if (cog.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cogHttpUrl 不能为空");
        }

        log.info("roboflow.workflow start instruction=\"{}\" cogHost={}", abbreviate(ins, 120), cogHostForLog(cog));

        String effBasePre = firstNonBlank(inferenceBaseUrlOverride, inferenceBaseUrl);
        String effWsPre = firstNonBlank(workspaceOverride, inferenceWorkspace);
        String effWfIdPre = firstNonBlank(workflowIdOverride, inferenceWorkflowId);
        boolean gpuWorkflow = !effBasePre.isBlank() && !effWsPre.isBlank() && !effWfIdPre.isBlank();
        int previewPixels = gpuWorkflow ? Math.min(workflowPreviewMaxSize, previewMaxSize) : previewMaxSize;
        byte[] jpeg = fetchTitilerPreviewJpeg(cog, previewPixels);
        if (jpeg.length > maxPreviewBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TiTiler 预览图过大（" + jpeg.length + " bytes），请减小 roboflow.preview-max-size 或检查影像");
        }
        log.info("roboflow.workflow titiler preview ok bytes={} previewPixels={}", jpeg.length, previewPixels);

        String b64 = Base64.getEncoder().encodeToString(jpeg);

        String effBase = firstNonBlank(inferenceBaseUrlOverride, inferenceBaseUrl);
        String effModel = firstNonBlank(modelIdOverride, inferenceModelId);
        String effTask = firstNonBlank(inferenceTaskOverride, inferenceTask);
        String effWs = firstNonBlank(workspaceOverride, inferenceWorkspace);
        String effWfId = firstNonBlank(workflowIdOverride, inferenceWorkflowId);

        if (!effBase.isBlank()) {
            if (!effWs.isBlank() && !effWfId.isBlank()) {
                return runGpuInferenceWorkflow(ins, b64, effBase, effWs, effWfId, pixelsPerUnitOverride);
            }
            if (!effModel.isBlank()) {
                return runGpuInferenceServer(ins, b64, effBase, effModel, effTask);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "自建推理须二选一：① 配置 roboflow.inference-workspace + roboflow.inference-workflow-id（跑 Workflow），"
                            + "或 ② 配置 roboflow.inference-model-id（跑单模型 /infer/{task}）。"
                            + "也可在 POST JSON 中传 workspaceName + workflowId，或 modelId。");
        }

        if (workflowUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "未配置 roboflow.inference-base-url 时必须有 roboflow.workflow-url");
        }

        return runServerlessWorkflow(ins, b64, pixelsPerUnitOverride);
    }

    private static String firstNonBlank(String override, String fallback) {
        if (override != null) {
            String t = override.trim().replaceAll("/$", "");
            if (!t.isEmpty()) {
                return t;
            }
        }
        if (fallback != null) {
            String t = fallback.trim().replaceAll("/$", "");
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    /**
     * 自建 Inference：默认 {@code POST /{workspace}/workflows/{id}}；legacy 为 {@code POST /infer/workflows/...}。
     */
    private JsonNode runGpuInferenceWorkflow(
            String instruction,
            String base64Jpeg,
            String baseUrl,
            String workspace,
            String workflowId,
            Double pixelsPerUnit
    ) {
        validateWorkflowPathSegment(workspace, "workspace");
        validateWorkflowPathSegment(workflowId, "workflow_id");

        ObjectNode image = MAPPER.createObjectNode();
        image.put("type", "base64");
        image.put("value", base64Jpeg);

        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.set("image", image);
        inputs.put("instruction", instruction);
        putPixelsPerUnitIfPresent(inputs, pixelsPerUnit);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("api_key", apiKey);
        body.set("inputs", inputs);

        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "构造 Roboflow Inference Workflow 请求失败");
        }

        String encWs = UriUtils.encodePathSegment(workspace, StandardCharsets.UTF_8);
        String encId = UriUtils.encodePathSegment(workflowId, StandardCharsets.UTF_8);
        String modernUrl = baseUrl + "/" + encWs + "/workflows/" + encId;
        String legacyUrl = baseUrl + "/infer/workflows/" + encWs + "/" + encId;

        if (workflowLegacyPathOnly) {
            return postWorkflowAndWrap(legacyUrl, json, instruction, baseUrl);
        }
        try {
            return postWorkflowAndWrap(modernUrl, json, instruction, baseUrl);
        } catch (ResponseStatusException ex) {
            int code = ex.getStatusCode().value();
            String detail = responseStatusExceptionMessage(ex);
            boolean maybeWrongRoute =
                    code == HttpStatus.NOT_FOUND.value()
                            || (code == HttpStatus.BAD_GATEWAY.value()
                            && (detail.contains("HTTP 400")
                            || detail.toLowerCase(Locale.ROOT).contains("invalid http")));
            if (maybeWrongRoute) {
                return postWorkflowAndWrap(legacyUrl, json, instruction, baseUrl);
            }
            throw ex;
        }
    }

    private static String responseStatusExceptionMessage(ResponseStatusException ex) {
        String r = ex.getReason();
        if (r != null && !r.isBlank()) {
            return r;
        }
        if (ex.getBody() != null && ex.getBody().getDetail() != null) {
            return ex.getBody().getDetail();
        }
        return "";
    }

    private JsonNode postWorkflowAndWrap(String inferUrl, String json, String instruction, String baseUrl) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(inferUrl))
                .timeout(inferenceTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        try {
            log.info(
                    "roboflow.workflow calling inference POST uri={} jsonBodyChars={} perRequestTimeoutMs={} inferenceMaxWallMs={}",
                    inferUrl,
                    json.length(),
                    inferenceTimeout.toMillis(),
                    inferenceMaxWallMs);
            HttpResponse<String> resp = executeInferencePostWithRetries(req);
            String respBody = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String hint400 = "";
                if (resp.statusCode() == 400 && respBody.toLowerCase(Locale.ROOT).contains("invalid http")) {
                    hint400 = " 【提示】若为超大 base64 请求体，可继续减小 roboflow.workflow-preview-max-size；或确认 Inference 版本支持当前 URL（modern /{ws}/workflows/{id} 与 legacy /infer/workflows/...）。";
                }
                if (resp.statusCode() == 404) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Roboflow Inference Workflow 404：" + inferUrl);
                }
                if (resp.statusCode() == 401) {
                    throw unauthorizedRoboflowInference(inferUrl, respBody);
                }
                String hint503 = "";
                if (resp.statusCode() == 503) {
                    hint503 = " 【提示】Inference 返回 503（model manager lock / 队列满 / 模型冷启动）。"
                            + "已对 503/429 退避重试至多 " + inferenceRetryMax + " 次。"
                            + " 建议：① 单卡 GPU 设 roboflow.inference-serialize-requests=true；"
                            + "② 加大 roboflow.inference-retry-base-delay-ms 或 inference-max-wall-ms；"
                            + "③ 看 inference 容器日志，必要时重启；④ 同机 Spring 与容器可改用 http://127.0.0.1:9001。";
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Roboflow Inference Workflow（" + inferUrl + "）HTTP " + resp.statusCode() + "："
                                + abbreviate(respBody, 600) + hint400 + hint503);
            }
            JsonNode root = MAPPER.readTree(respBody);
            if (root == null || root.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Roboflow Inference Workflow 返回空 JSON");
            }
            log.info("roboflow.workflow inference ok http={} responseChars={}", resp.statusCode(), respBody.length());
            ObjectNode wrapped = MAPPER.createObjectNode();
            wrapped.put("source", "roboflow_inference_workflow");
            wrapped.put("inference_url", inferUrl);
            wrapped.put("instruction_echo", instruction);
            wrapped.set("result", root);
            return wrapped;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Roboflow Inference Workflow 请求被中断");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用 Roboflow Inference Workflow 失败（请确认容器已启动且 " + baseUrl + " 从本服务可达）：" + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "解析 Roboflow Inference Workflow 响应失败：" + e.getMessage());
        }
    }

    /**
     * Inference 在模型加载或互斥锁忙时常返回 503（Could not acquire model manager lock…）或 429，宜退避重试。
     * 受 {@code roboflow.inference-max-wall-ms} 约束，避免长时间无响应导致前端一直停在「正在调用…」。
     */
    private HttpResponse<String> executeInferencePostWithRetries(HttpRequest req) throws IOException, InterruptedException {
        long startMs = System.currentTimeMillis();
        long inferMs = inferenceTimeout.toMillis();
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= inferenceRetryMax; attempt++) {
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed >= inferenceMaxWallMs) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Roboflow Inference 总耗时已超过 roboflow.inference-max-wall-ms（"
                                + inferenceMaxWallMs + "ms），已中止重试。可调大该值或检查 GPU 推理是否正常。");
            }
            long remaining = inferenceMaxWallMs - elapsed;
            if (remaining < inferMs) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Roboflow Inference 剩余墙上时间不足以完成下一次请求（单次约需 "
                                + inferMs + "ms，剩余 " + remaining + "ms）。可调大 roboflow.inference-max-wall-ms。");
            }

            long sendStart = System.currentTimeMillis();
            log.info(
                    "roboflow.inference httpClient.send start attemptIndex={} maxAttemptIndex={} uri={}",
                    attempt,
                    inferenceRetryMax,
                    req.uri());
            if (inferenceSerializeRequests) {
                synchronized (inferenceHttpLock) {
                    resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                }
            } else {
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            long sendMs = System.currentTimeMillis() - sendStart;
            int code = resp.statusCode();
            log.info("roboflow.inference httpClient.send done attempt={} httpStatus={} elapsedMs={}", attempt, code, sendMs);
            if ((code == 503 || code == 429) && attempt < inferenceRetryMax) {
                long backoff = inferenceRetryBaseDelayMs * (1L << attempt);
                if (backoff > inferenceRetryDelayCapMs) {
                    backoff = inferenceRetryDelayCapMs;
                }
                elapsed = System.currentTimeMillis() - startMs;
                remaining = inferenceMaxWallMs - elapsed;
                long reserveForNext = inferMs + 1000L;
                long sleepMs = Math.min(backoff, Math.max(0L, remaining - reserveForNext));
                if (sleepMs < 1L) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                            "Roboflow Inference 503/429 重试前已无足够剩余墙上时间（剩余约 " + remaining + "ms）。可调大 roboflow.inference-max-wall-ms。");
                }
                String snippet = abbreviate(resp.body() == null ? "" : resp.body(), 240);
                log.warn(
                        "roboflow.inference got {} will sleepMs={} then retry (attempt next={}) snippet={}",
                        code,
                        sleepMs,
                        attempt + 1,
                        snippet);
                Thread.sleep(sleepMs);
                continue;
            }
            break;
        }
        return resp;
    }

    /** Roboflow 工作流常见输入名：pixels_per_unit（每像素米数） */
    private static void putPixelsPerUnitIfPresent(ObjectNode inputs, Double pixelsPerUnit) {
        if (pixelsPerUnit == null || !Double.isFinite(pixelsPerUnit) || pixelsPerUnit <= 0) {
            return;
        }
        inputs.put("pixels_per_unit", pixelsPerUnit);
    }

    private static void validateWorkflowPathSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 " + name + "：不能为空");
        }
        if (value.contains("/") || value.contains("..") || value.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 " + name + "：不得包含路径分隔符或 ..");
        }
    }

    /** POST {baseUrl}/infer/{task}，见 https://inference.roboflow.com/quickstart/http_inference/ */
    private JsonNode runGpuInferenceServer(String instruction, String base64Jpeg, String baseUrl, String modelId, String taskRaw) {
        String task = sanitizeInferenceTask(taskRaw);
        String inferUrl = baseUrl + "/infer/" + task;

        ObjectNode image = MAPPER.createObjectNode();
        image.put("type", "base64");
        image.put("value", base64Jpeg);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model_id", modelId);
        body.set("image", image);
        body.put("api_key", apiKey);
        body.put("confidence", inferenceConfidence);
        body.put("iou_threshold", inferenceIouThreshold);

        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "构造 Roboflow Inference 请求失败");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(inferUrl))
                .timeout(inferenceTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        try {
            log.info(
                    "roboflow infer-server calling POST uri={} jsonBodyChars={} perRequestTimeoutMs={}",
                    inferUrl,
                    json.length(),
                    inferenceTimeout.toMillis());
            HttpResponse<String> resp = executeInferencePostWithRetries(req);
            String respBody = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                if (resp.statusCode() == 401) {
                    throw unauthorizedRoboflowInference(inferUrl, respBody);
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Roboflow Inference（" + inferUrl + "）HTTP " + resp.statusCode() + "："
                                + abbreviate(respBody, 600));
            }
            JsonNode root = MAPPER.readTree(respBody);
            if (root == null || root.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Roboflow Inference 返回空 JSON");
            }
            log.info("roboflow infer-server ok http={} responseChars={}", resp.statusCode(), respBody.length());
            ObjectNode wrapped = MAPPER.createObjectNode();
            wrapped.put("source", "roboflow_inference_server");
            wrapped.put("inference_url", inferUrl);
            wrapped.put("instruction_echo", instruction);
            wrapped.set("result", root);
            return wrapped;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Roboflow Inference 请求被中断");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用 Roboflow Inference 失败（请确认容器已启动且 " + baseUrl + " 从本服务可达）：" + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "解析 Roboflow Inference 响应失败：" + e.getMessage());
        }
    }

    private static String sanitizeInferenceTask(String raw) {
        if (raw == null || raw.isBlank()) {
            return "object_detection";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!INFERENCE_TASKS.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "roboflow.inference-task 必须是 object_detection、classification 或 instance_segmentation，当前：" + raw);
        }
        return t;
    }

    private JsonNode runServerlessWorkflow(String instruction, String base64Jpeg, Double pixelsPerUnit) {
        ObjectNode image = MAPPER.createObjectNode();
        image.put("type", "base64");
        image.put("value", base64Jpeg);

        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.set("image", image);
        inputs.put("instruction", instruction);
        putPixelsPerUnitIfPresent(inputs, pixelsPerUnit);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("api_key", apiKey);
        body.set("inputs", inputs);

        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "构造 Roboflow 请求失败");
        }

        URI wfUri = URI.create(workflowUrl);
        String origin = wfUri.getScheme() != null && wfUri.getHost() != null
                ? wfUri.getScheme() + "://" + wfUri.getHost()
                : "https://serverless.roboflow.com";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(wfUri)
                .timeout(serverlessTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Origin", origin)
                .header("Referer", origin + "/")
                .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String respBody = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String hint = cloudflareHintIfHtml(respBody, resp.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Roboflow 工作流 HTTP " + resp.statusCode() + "：" + abbreviate(respBody, 500) + hint);
            }
            JsonNode root = MAPPER.readTree(respBody);
            if (root == null || root.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Roboflow 返回空 JSON");
            }
            return root;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Roboflow 请求被中断");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "调用 Roboflow 失败：" + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "解析 Roboflow 响应失败：" + e.getMessage());
        }
    }

    private byte[] fetchTitilerPreviewJpeg(String cogHttpUrl, int maxSizePixels) {
        int maxPx = Math.max(64, Math.min(4096, maxSizePixels));
        IOException last = null;
        String[] paths = {"/cog/preview.jpg", "/cog/preview.jpeg", "/cog/preview.png"};
        for (String path : paths) {
            String uri = UriComponentsBuilder.fromUriString(titilerBaseUrl + path)
                    .queryParam("url", cogHttpUrl)
                    .queryParam("max_size", maxPx)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUriString();
            try {
                byte[] body = sendTitilerPreviewGetOrNull(uri);
                if (body != null && body.length > 0) {
                    return body;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "TiTiler 预览请求被中断");
            } catch (IOException e) {
                last = e;
            }
        }
        String hint = last != null ? last.getMessage() : "无详情";
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "无法从 TiTiler 生成预览图（" + titilerBaseUrl + "/cog/preview.jpg）。请确认 TiTiler 已启动且能访问 COG URL。原因：" + hint);
    }

    private byte[] sendTitilerPreviewGetOrNull(String uri) throws IOException, InterruptedException {
        HttpRequest previewReq = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(titilerTimeout)
                .header("Accept", "image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET()
                .build();
        HttpResponse<byte[]> previewResp = httpClient.send(previewReq, HttpResponse.BodyHandlers.ofByteArray());
        if (previewResp.statusCode() >= 200 && previewResp.statusCode() < 300) {
            byte[] body = previewResp.body();
            if (body != null && body.length > 0) {
                return body;
            }
        }
        return null;
    }

    private static ResponseStatusException unauthorizedRoboflowInference(String inferUrl, String respBody) {
        String apiMsg = extractRoboflowApiMessage(respBody);
        String tail = apiMsg != null ? apiMsg : abbreviate(respBody, 500);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Roboflow API Key 无效或与当前 workspace 不匹配（Inference 401）。"
                        + " 请在 Roboflow 控制台该 workspace 下生成 Private API Key，写入 roboflow.api-key 或环境变量 ROBOFLOW_API_KEY。"
                        + " 详情：" + tail + " 请求：" + inferUrl);
    }

    /** Inference / Roboflow 常见 JSON：{"message":"..."} */
    private static String extractRoboflowApiMessage(String respBody) {
        if (respBody == null || respBody.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(respBody);
            if (n != null && n.hasNonNull("message") && n.get("message").isTextual()) {
                String m = n.get("message").asText().trim();
                return m.isEmpty() ? null : m;
            }
        } catch (JsonProcessingException ignored) {
            return null;
        }
        return null;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    private static String cogHostForLog(String cogUrl) {
        try {
            URI u = URI.create(cogUrl);
            String host = u.getHost();
            int port = u.getPort();
            if (host == null) {
                return "(no-host)";
            }
            return port > 0 ? host + ":" + port : host;
        } catch (Exception e) {
            return "(invalid-url)";
        }
    }

    private static String cloudflareHintIfHtml(String body, int status) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String lower = body.toLowerCase();
        if (status == 403 && (lower.contains("cloudflare") || lower.contains("<!doctype html"))) {
            return " 【说明】响应为 Cloudflare 拦截页：可改用自建推理容器并配置 roboflow.inference-base-url（见 application.properties 注释）。";
        }
        return "";
    }
}
