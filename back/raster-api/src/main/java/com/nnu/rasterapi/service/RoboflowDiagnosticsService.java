package com.nnu.rasterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 地物提取链路自检：TiTiler 预览、COG 可达性、GPU Inference 连通性。
 */
@Service
public class RoboflowDiagnosticsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String titilerBaseUrl;
    private final String inferenceBaseUrl;
    private final String inferenceWorkspace;
    private final String inferenceWorkflowId;
    private final boolean apiKeyConfigured;
    private final String titilerCogFetchBaseHint;

    public RoboflowDiagnosticsService(
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${roboflow.inference-base-url:}") String inferenceBaseUrl,
            @Value("${roboflow.inference-workspace:}") String inferenceWorkspace,
            @Value("${roboflow.inference-workflow-id:}") String inferenceWorkflowId,
            @Value("${roboflow.api-key:}") String apiKey,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${app.titiler-cog-base-url:}") String titilerCogBaseUrl
    ) {
        this.titilerBaseUrl = trimSlash(titilerBaseUrl);
        this.inferenceBaseUrl = trimSlash(inferenceBaseUrl);
        this.inferenceWorkspace = inferenceWorkspace == null ? "" : inferenceWorkspace.trim();
        this.inferenceWorkflowId = inferenceWorkflowId == null ? "" : inferenceWorkflowId.trim();
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        String cogBase = titilerCogBaseUrl == null || titilerCogBaseUrl.isBlank()
                ? trimSlash(publicBaseUrl)
                : trimSlash(titilerCogBaseUrl);
        this.titilerCogFetchBaseHint = cogBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public ObjectNode diagnose(String cogHttpUrl) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode hints = out.putArray("hints");

        out.put("apiKeyConfigured", apiKeyConfigured);
        out.put("titilerBaseUrl", titilerBaseUrl);
        out.put("inferenceBaseUrl", inferenceBaseUrl.isBlank() ? "(未配置，将走 serverless)" : inferenceBaseUrl);
        out.put("inferenceWorkspace", inferenceWorkspace);
        out.put("inferenceWorkflowId", inferenceWorkflowId);
        out.put("cogUrlForTitiler", titilerCogFetchBaseHint + "/api/upload/raster/file/{uuid}.tif");

        out.set("titiler", probeTitilerRoot());
        if (cogHttpUrl != null && !cogHttpUrl.isBlank()) {
            out.set("uploadedCog", probeCogChain(cogHttpUrl.trim(), hints));
        } else {
            hints.add("未传 cogHttpUrl：上传影像后可在浏览器网络面板复制 workflow 请求体中的 cogHttpUrl 再调用本接口。");
        }
        if (!inferenceBaseUrl.isBlank()) {
            out.set("inference", probeInference(inferenceBaseUrl));
        } else {
            hints.add("roboflow.inference-base-url 为空：地物提取将走 serverless（易被 Cloudflare 拦截）。");
        }

        if (!apiKeyConfigured) {
            hints.add("未配置 roboflow.api-key / ROBOFLOW_API_KEY，workflow 会直接失败。");
        }
        if (titilerBaseUrl.contains("localhost") || titilerBaseUrl.contains("127.0.0.1")) {
            hints.add("TiTiler 指向本机：若 TiTiler 在 Docker 内，须用 host.docker.internal 或局域网 IP，且 app.titiler-cog-base-url 须是 TiTiler 能访问的 Spring 地址。");
        }
        if (!inferenceBaseUrl.isBlank() && inferenceBaseUrl.contains("117.50.75.234")
                && !inferenceBaseUrl.contains("127.0.0.1")) {
            hints.add("Inference 使用公网 IP：若 Spring 与 Docker 在同一台云机，建议改为 http://127.0.0.1:9001。");
        }

        out.set("hints", hints);
        return out;
    }

    private ObjectNode probeCogChain(String cogHttpUrl, ArrayNode hints) {
        ObjectNode cog = MAPPER.createObjectNode();
        cog.put("cogHttpUrl", cogHttpUrl);

        ProbeResult springGet = httpProbe(cogHttpUrl, "GET", 20);
        cog.set("springCanGetCog", toNode(springGet));
        if (!springGet.ok()) {
            hints.add("Spring 无法 GET 上传 COG（" + springGet.summary() + "）。检查 app.public-base-url、/api/upload/raster/file 映射与文件是否存在。");
        }

        String infoUri = UriComponentsBuilder.fromUriString(titilerBaseUrl + "/cog/info")
                .queryParam("url", cogHttpUrl)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
        ProbeResult titilerInfo = httpProbe(infoUri, "GET", 45);
        cog.set("titilerCogInfo", toNode(titilerInfo));
        if (!titilerInfo.ok()) {
            hints.add("TiTiler 无法读取该 COG（" + titilerInfo.summary() + "）。"
                    + " 常见原因：TiTiler 在 Docker 内访问不到 " + titilerCogFetchBaseHint
                    + "，请设置 app.titiler-cog-base-url 为 TiTiler 可达的地址。");
        } else {
            hints.add("TiTiler 可读 COG：road/water 卡住时多半在 GPU Inference 排队或冷启动（看 inference 节点）。");
        }

        String previewUri = UriComponentsBuilder.fromUriString(titilerBaseUrl + "/cog/preview.jpg")
                .queryParam("url", cogHttpUrl)
                .queryParam("max_size", 256)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
        ProbeResult preview = httpProbe(previewUri, "GET", 60);
        cog.set("titilerPreviewSample", toNode(preview));
        if (!preview.ok()) {
            hints.add("TiTiler 预览图失败（" + preview.summary() + "），workflow 会在「正在调用」阶段长时间等待或超时。");
        }
        return cog;
    }

    private ObjectNode probeTitilerRoot() {
        ProbeResult r = httpProbe(titilerBaseUrl + "/", "GET", 8);
        ObjectNode n = toNode(r);
        if (!r.ok()) {
            n.put("advice", "请先启动 TiTiler（默认 :8000），并确认 live.titiler.base-url");
        }
        return n;
    }

    private ObjectNode probeInference(String base) {
        ObjectNode n = MAPPER.createObjectNode();
        ProbeResult root = httpProbe(base + "/", "GET", 10);
        n.set("root", toNode(root));
        ProbeResult docs = httpProbe(base + "/docs", "GET", 10);
        n.set("docs", toNode(docs));
        if (!root.ok() && !docs.ok()) {
            n.put("advice", "Inference 不可达：检查 docker、端口 9001、安全组；Spring 须能访问该地址。");
        } else if (root.statusCode == 502 || docs.statusCode == 502) {
            n.put("advice", "Inference 返回 502：容器在但内部异常，请 docker logs 查看 OOM/GPU。");
        } else {
            n.put("advice", "HTTP 可达；若 workflow 仍慢，多为 model manager lock 冷启动，等 2–5 分钟且勿连点。");
        }
        return n;
    }

    private ProbeResult httpProbe(String url, String method, int timeoutSec) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Accept", "*/*");
            if ("HEAD".equalsIgnoreCase(method)) {
                b.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                b.GET();
            }
            HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            boolean ok = code >= 200 && code < 300;
            String bodySample = "";
            byte[] body = resp.body();
            if (body != null && body.length > 0) {
                bodySample = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ");
            }
            return new ProbeResult(ok, code, bodySample, null);
        } catch (Exception e) {
            return new ProbeResult(false, -1, "", e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()));
        }
    }

    private static ObjectNode toNode(ProbeResult r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ok", r.ok());
        n.put("statusCode", r.statusCode);
        if (r.bodySample != null && !r.bodySample.isBlank()) {
            n.put("bodySample", r.bodySample);
        }
        if (r.error != null && !r.error.isBlank()) {
            n.put("error", r.error);
        }
        return n;
    }

    private record ProbeResult(boolean ok, int statusCode, String bodySample, String error) {
        String summary() {
            if (error != null && !error.isBlank()) {
                return error;
            }
            return "HTTP " + statusCode + (bodySample == null || bodySample.isBlank() ? "" : " — " + bodySample);
        }
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/$", "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
