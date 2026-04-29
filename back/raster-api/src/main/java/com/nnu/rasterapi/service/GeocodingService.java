package com.nnu.rasterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GeocodingService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, double[]> CITY_FALLBACK_BBOX = createCityFallbackBbox();

    private final HttpClient httpClient;
    private final List<String> geocodeBaseUrls;
    private final String userAgent;
    private final Duration requestTimeout;

    public GeocodingService(
            @Value("${geocode.nominatim-base-urls:https://nominatim.openstreetmap.org,https://nominatim.openstreetmap.fr}") String baseUrls,
            @Value("${geocode.user-agent:precision-observer/1.0}") String userAgent,
            @Value("${geocode.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${geocode.request-timeout-ms:8000}") long requestTimeoutMs
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.geocodeBaseUrls = parseBaseUrls(baseUrls);
        this.userAgent = userAgent;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
    }

    public CityGeocodeResult geocodeCity(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "城市名称不能为空");
        }

        ResponseStatusException lastError = null;
        for (String baseUrl : geocodeBaseUrls) {
            try {
                return geocodeFromBaseUrl(baseUrl, trimmed);
            } catch (ResponseStatusException ex) {
                // 404 代表这个查询在该源没结果，继续尝试其他源
                if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                    lastError = ex;
                }
            }
        }

        if (lastError != null) {
            CityGeocodeResult fallback = geocodeFromFallback(trimmed);
            if (fallback != null) {
                return fallback;
            }
            throw lastError;
        }
        CityGeocodeResult fallback = geocodeFromFallback(trimmed);
        if (fallback != null) {
            return fallback;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到匹配城市");
    }

    private CityGeocodeResult geocodeFromBaseUrl(String baseUrl, String query) {
        String url = baseUrl.replaceAll("/$", "") +
                "/search?format=jsonv2&limit=1&addressdetails=1&polygon_geojson=0&q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码服务调用失败: " + response.statusCode());
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (!root.isArray() || root.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到匹配城市");
            }

            JsonNode first = root.get(0);
            JsonNode bbox = first.path("boundingbox");
            if (!bbox.isArray() || bbox.size() < 4) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码结果缺少边界框");
            }

            // Nominatim bbox order: south, north, west, east
            double minLat = parseDoubleNode(bbox.get(0), "minLat");
            double maxLat = parseDoubleNode(bbox.get(1), "maxLat");
            double minLng = parseDoubleNode(bbox.get(2), "minLng");
            double maxLng = parseDoubleNode(bbox.get(3), "maxLng");
            String displayName = first.path("display_name").asText(query);

            return new CityGeocodeResult(query, displayName, minLng, minLat, maxLng, maxLat);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码服务请求被中断", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码服务不可用", e);
        }
    }

    private static List<String> parseBaseUrls(String baseUrls) {
        String[] split = baseUrls.split(",");
        List<String> result = new ArrayList<>();
        for (String item : split) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add("https://nominatim.openstreetmap.org");
        }
        return result;
    }

    private static CityGeocodeResult geocodeFromFallback(String query) {
        String key = normalizeCityKey(query);
        double[] bbox = CITY_FALLBACK_BBOX.get(key);
        if (bbox == null) {
            return null;
        }
        return new CityGeocodeResult(query, key, bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    private static String normalizeCityKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replace("市", "");
        value = value.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "nanjing" -> "南京";
            case "beijing" -> "北京";
            case "shanghai" -> "上海";
            case "guangzhou" -> "广州";
            case "shenzhen" -> "深圳";
            case "wuhan" -> "武汉";
            case "hangzhou" -> "杭州";
            case "chengdu" -> "成都";
            case "xian", "xi'an" -> "西安";
            case "chongqing" -> "重庆";
            default -> value;
        };
    }

    private static Map<String, double[]> createCityFallbackBbox() {
        Map<String, double[]> map = new HashMap<>();
        // [minLng, minLat, maxLng, maxLat]
        map.put("北京", new double[]{115.42, 39.44, 117.50, 41.06});
        map.put("上海", new double[]{120.85, 30.66, 122.20, 31.88});
        map.put("广州", new double[]{112.95, 22.45, 114.20, 23.95});
        map.put("深圳", new double[]{113.75, 22.45, 114.65, 22.95});
        map.put("南京", new double[]{118.35, 31.20, 119.30, 32.65});
        map.put("杭州", new double[]{119.85, 29.95, 120.90, 30.70});
        map.put("武汉", new double[]{113.65, 29.95, 115.10, 31.40});
        map.put("成都", new double[]{102.90, 30.05, 104.90, 31.45});
        map.put("西安", new double[]{108.40, 33.70, 109.50, 34.75});
        map.put("重庆", new double[]{105.30, 28.15, 110.20, 32.20});
        return map;
    }

    private static double parseDoubleNode(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码结果缺少字段: " + fieldName);
        }
        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码字段格式错误: " + fieldName);
        }
    }

    public record CityGeocodeResult(
            String query,
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat
    ) {
    }
}
