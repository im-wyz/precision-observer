package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SpectralIndexAnalysisService {
    private final LiveImageryService liveImageryService;
    private final String titilerBaseUrl;
    private final int tileSize;

    public SpectralIndexAnalysisService(
            LiveImageryService liveImageryService,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize
    ) {
        this.liveImageryService = liveImageryService;
        this.titilerBaseUrl = titilerBaseUrl;
        this.tileSize = tileSize;
    }

    public Map<String, Object> analyze(
            String message,
            String place,
            String startDate,
            String endDate,
            String indexKey,
            boolean preferLocal
    ) {
        String targetPlace = place == null || place.isBlank() ? extractPlace(message) : place.trim();
        if (targetPlace.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少分析地区");
        }
        IndexSpec spec = indexSpec(indexKey);
        LocalDate start = parseDateOrNull(startDate);
        LocalDate end = parseDateOrNull(endDate);

        LiveImageryService.LiveImageryResult imagery = liveImageryService.queryByPlaceNameForBandAnalysis(targetPlace, start, end);
        LiveImageryService.SelectedScene scene = imagery.selectedScenes().isEmpty() ? null : imagery.selectedScenes().get(0);
        if (scene == null || scene.cogUrl() == null || scene.cogUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可用于指数分析的本地多波段影像");
        }

        String cogUrl = stripLocalParams(scene.cogUrl());
        String tileUrl = buildIndexTileTemplate(cogUrl, spec);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("index_key", spec.key());
        metrics.put("data_source", "local-cog");
        metrics.put("valid_pixel_ratio", null);
        metrics.put("min", null);
        metrics.put("max", null);
        metrics.put("mean", null);
        metrics.put("study_area_km2", bboxAreaKm2(imagery.minLng(), imagery.minLat(), imagery.maxLng(), imagery.maxLat()));
        if (message != null && message.contains("面积")) {
            metrics.put("threshold_area_rule", spec.thresholdRule());
            metrics.put("threshold_area_km2", null);
        }

        Map<String, Object> out = baseResult("spectral_index", imagery, tileUrl, cogUrl);
        out.put("sourceKind", "local-cog");
        out.put("sourceSceneId", scene.itemId());
        out.put("indexKey", spec.key());
        out.put("bandMap", bandMap());
        out.put("metrics", metrics);
        out.put("reportTitle", targetPlace + " " + spec.key() + " 指数分析报告");
        out.put("reportSummary", buildIndexReport(targetPlace, startDate, endDate, spec, scene));
        out.put("message", spec.key() + " 指数分析完成");
        out.put("warnings", List.of("本地接口已恢复指数瓦片与数据源说明；像元统计如需精确值，请确认 TiTiler statistics 服务可用后再启用。"));
        return out;
    }

    private String buildIndexTileTemplate(String cogUrl, IndexSpec spec) {
        String url = URLEncoder.encode(cogUrl, StandardCharsets.UTF_8);
        String expression = URLEncoder.encode(spec.expression(), StandardCharsets.UTF_8);
        String colormap = switch (spec.key()) {
            case "NDWI" -> "blues";
            case "NDBI" -> "inferno";
            case "NBR" -> "rdylgn";
            default -> "viridis";
        };
        return titilerBaseUrl.replaceAll("/$", "")
                + "/cog/tiles/WebMercatorQuad/{z}/{x}/{y}.png"
                + "?url=" + url
                + "&tilesize=" + tileSize
                + "&expression=" + expression
                + "&rescale=-1,1"
                + "&colormap_name=" + colormap;
    }

    private static Map<String, Object> baseResult(
            String analysisType,
            LiveImageryService.LiveImageryResult imagery,
            String tileUrl,
            String downloadUrl
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("analysisType", analysisType);
        out.put("tileTemplateUrl", tileUrl);
        out.put("downloadUrl", downloadUrl);
        out.put("extent", extent(imagery));
        out.put("boundaries", imagery.boundaries());
        return out;
    }

    private static Map<String, Object> extent(LiveImageryService.LiveImageryResult imagery) {
        Map<String, Object> extent = new LinkedHashMap<>();
        extent.put("minLng", imagery.minLng());
        extent.put("minLat", imagery.minLat());
        extent.put("maxLng", imagery.maxLng());
        extent.put("maxLat", imagery.maxLat());
        return extent;
    }

    private static String buildIndexReport(
            String place,
            String startDate,
            String endDate,
            IndexSpec spec,
            LiveImageryService.SelectedScene scene
    ) {
        return "## " + place + " " + spec.key() + " 指数分析\n\n"
                + "基于本地 Sentinel-2 SR 多波段 COG 生成 " + spec.name() + " 图层。\n\n"
                + "| 项目 | 内容 |\n"
                + "|---|---|\n"
                + "| 数据源 | local-cog |\n"
                + "| 时间范围 | " + safeRange(startDate, endDate) + " |\n"
                + "| 影像 | " + scene.itemId() + " |\n"
                + "| 使用波段 | " + spec.bandsText() + " |\n"
                + "| 指数公式 | " + spec.formulaText() + " |\n\n"
                + "地图右侧显示指数瓦片，边界用于定位研究区。";
    }

    private static String safeRange(String startDate, String endDate) {
        String s = startDate == null || startDate.isBlank() ? "未指定" : startDate;
        String e = endDate == null || endDate.isBlank() ? "未指定" : endDate;
        return s + " ~ " + e;
    }

    private static String stripLocalParams(String raw) {
        int idx = raw.indexOf("|");
        return idx >= 0 ? raw.substring(0, idx) : raw;
    }

    private static Map<String, Integer> bandMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("B4", 1);
        map.put("B8", 2);
        map.put("B2", 3);
        map.put("B3", 4);
        map.put("B5", 5);
        map.put("B6", 6);
        map.put("B7", 7);
        map.put("B8A", 8);
        map.put("B11", 9);
        map.put("B12", 10);
        return map;
    }

    private static IndexSpec indexSpec(String indexKey) {
        String key = indexKey == null ? "NDVI" : indexKey.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "NDWI" -> new IndexSpec("NDWI", "水体指数", "(b4-b2)/(b4+b2)", "B3、B8", "NDWI=(B3-B8)/(B3+B8)", "NDWI >= 0.2");
            case "NDBI" -> new IndexSpec("NDBI", "建筑指数", "(b9-b2)/(b9+b2)", "B11、B8", "NDBI=(B11-B8)/(B11+B8)", "NDBI >= 0.1");
            case "NBR" -> new IndexSpec("NBR", "火烧指数", "(b2-b10)/(b2+b10)", "B8、B12", "NBR=(B8-B12)/(B8+B12)", "NBR <= 0.1");
            case "SAVI" -> new IndexSpec("SAVI", "土壤调节植被指数", "1.5*(b2-b1)/(b2+b1+0.5)", "B8、B4", "SAVI=1.5*(B8-B4)/(B8+B4+0.5)", "SAVI >= 0.3");
            default -> new IndexSpec("NDVI", "归一化植被指数", "(b2-b1)/(b2+b1)", "B8、B4", "NDVI=(B8-B4)/(B8+B4)", "NDVI >= 0.3");
        };
    }

    private static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractPlace(String message) {
        if (message == null) return "";
        for (String place : List.of("南京", "太湖", "巢湖", "鄱阳湖", "苏州", "无锡", "上海", "北京")) {
            if (message.contains(place)) return place;
        }
        return "";
    }

    private static double bboxAreaKm2(double minLng, double minLat, double maxLng, double maxLat) {
        double midLat = Math.toRadians((minLat + maxLat) / 2.0);
        double width = Math.abs(maxLng - minLng) * 111.32 * Math.cos(midLat);
        double height = Math.abs(maxLat - minLat) * 110.57;
        return Math.round(width * height * 100.0) / 100.0;
    }

    private record IndexSpec(
            String key,
            String name,
            String expression,
            String bandsText,
            String formulaText,
            String thresholdRule
    ) {
    }
}
