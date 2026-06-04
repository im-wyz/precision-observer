package com.nnu.rasterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 意图解析：默认接阿里云 DashScope OpenAI 兼容接口（通义等），请求体与 OpenAI chat/completions 一致。
 */
@Service
public class LlmIntentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern YYYY_MM = Pattern.compile("(20\\d{2})\\s*年\\s*(1[0-2]|0?[1-9])\\s*月");
    private static final Pattern PLACE_TOKEN = Pattern.compile("([\\u4e00-\\u9fa5]{2,20})(市|区|县)?");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public LlmIntentTool(
            @Value("${llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:qwen3.6-plus}") String model,
            @Value("${llm.timeout-ms:15000}") long timeoutMs
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public ParsedIntent parseIntent(String message) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "LLM API Key 未配置（请设置环境变量 LLM_API_KEY）");
        }
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "LLM 模型标识未配置（llm.model）");
        }

        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.1);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", """
你是遥感分析指令解析器。请把用户输入解析成 JSON，格式严格如下：
{
  "intent": "cropland_change" | "unknown",
  "place": "南京",
  "startMonth": "2017-03",
  "endMonth": "2018-03"
}
规则：
1) 当前只识别“耕地面积变化/对比/分析”场景，其他返回 intent=unknown
2) startMonth/endMonth 必须是 yyyy-MM，不足时留空字符串
3) 只输出 JSON，不要附加解释
""");
            messages.addObject().put("role", "user").put("content", message);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                // LLM 不可用（如余额不足/网络故障）时，降级到本地规则解析，保证功能可用
                return parseIntentFallback(message);
            }

            JsonNode root = MAPPER.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 返回内容为空");
            }

            String jsonText = extractJson(content);
            JsonNode parsed = MAPPER.readTree(jsonText);
            String intent = parsed.path("intent").asText("unknown");
            String place = parsed.path("place").asText("");
            YearMonth start = parseYearMonth(parsed.path("startMonth").asText(""));
            YearMonth end = parseYearMonth(parsed.path("endMonth").asText(""));
            return new ParsedIntent(intent, place, start, end);
        } catch (ResponseStatusException e) {
            // 对外部服务异常也做降级，避免中断主流程
            return parseIntentFallback(message);
        } catch (Exception e) {
            return parseIntentFallback(message);
        }
    }

    private static String extractJson(String raw) {
        Matcher m = JSON_BLOCK.matcher(raw);
        if (m.find()) return m.group();
        return raw.trim();
    }

    private static YearMonth parseYearMonth(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return YearMonth.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 本地规则兜底：
     * - 识别“耕地/农田 + 变化/对比/分析”
     * - 提取地名与两个年月
     */
    private static ParsedIntent parseIntentFallback(String message) {
        String text = message == null ? "" : message.trim();
        boolean croplandIntent = (text.contains("耕地") || text.contains("农田") || text.contains("耕地面积"))
                && (text.contains("变化") || text.contains("对比") || text.contains("比较") || text.contains("分析"));
        if (!croplandIntent) {
            return new ParsedIntent("unknown", "", null, null);
        }

        String place = parsePlaceFallback(text);
        YearMonth start = null;
        YearMonth end = null;
        Matcher m = YYYY_MM.matcher(text);
        if (m.find()) {
            start = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (m.find()) {
                end = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        return new ParsedIntent("cropland_change", place == null ? "" : place, start, end);
    }

    private static String parsePlaceFallback(String text) {
        // 去掉月份片段，避免把“月”误当成地名前缀的一部分（例如“月南京”）
        String withoutMonth = text
                .replaceAll("(20\\d{2})\\s*年\\s*", " ")
                .replaceAll("(1[0-2]|0?[1-9])\\s*月", " ");

        String cleaned = withoutMonth
                .replaceAll("[，。,.!?！？]", " ")
                .replaceAll("(分析|比较|对比|变化|耕地面积|耕地|农田|从|到|之间|和|的|我要|请|帮我)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Matcher m = PLACE_TOKEN.matcher(cleaned);
        if (m.find()) {
            String place = m.group(1);
            // 防御：若仍出现前缀“月”，去掉
            if (place.startsWith("月") && place.length() > 2) {
                return place.substring(1);
            }
            return place;
        }
        return "";
    }

    public record ParsedIntent(String intent, String place, YearMonth startMonth, YearMonth endMonth) {}
}
