package com.nnu.rasterapi.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RemoteSensingBasicService {
    private final LiveImageryService liveImageryService;
    private final LocalImageryArchiveService localImageryArchiveService;
    private final RemoteSensingPreprocessService preprocessService;

    public RemoteSensingBasicService(
            LiveImageryService liveImageryService,
            LocalImageryArchiveService localImageryArchiveService,
            RemoteSensingPreprocessService preprocessService
    ) {
        this.liveImageryService = liveImageryService;
        this.localImageryArchiveService = localImageryArchiveService;
        this.preprocessService = preprocessService;
    }

    public Map<String, Object> analyze(
            String message,
            String place,
            String startDate,
            String endDate,
            String compareStartDate,
            String compareEndDate,
            String analysisType,
            boolean preferLocal
    ) {
        String type = analysisType == null || analysisType.isBlank() ? "catalog_check" : analysisType.trim();
        if ("preprocess".equals(type)) {
            return preprocessService.plan(message, place, startDate, endDate);
        }
        if ("catalog_check".equals(type)) {
            return catalogCheck(place, startDate, endDate);
        }
        return imageryBackedResult(type, message, place, startDate, endDate);
    }

    private Map<String, Object> imageryBackedResult(String type, String message, String place, String startDate, String endDate) {
        String targetPlace = place == null || place.isBlank() ? extractPlace(message) : place.trim();
        LiveImageryService.LiveImageryResult imagery = liveImageryService.queryByPlaceNameForCroplandCompare(
                targetPlace,
                parseDateOrNull(startDate),
                parseDateOrNull(endDate)
        );
        String tile = "";
        String download = "";
        if (!imagery.cogUrls().isEmpty()) {
            download = imagery.cogUrls().get(0);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("extent", extent(imagery));
        extra.put("boundaries", imagery.boundaries());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("analysisType", type);
        out.put("sourceKind", "local-cog");
        out.put("tileTemplateUrl", tile);
        out.put("downloadUrl", download);
        out.put("extent", extent(imagery));
        out.put("boundaries", imagery.boundaries());
        out.put("metrics", Map.of(
                "data_source", "local-cog",
                "coverage_ratio", imagery.coverageRatio()
        ));
        out.put("extra", extra);
        out.put("reportTitle", titleFor(type));
        out.put("reportSummary", reportFor(type, targetPlace, startDate, endDate));
        out.put("message", titleFor(type) + "完成");
        out.put("warnings", List.of("基础处理接口已恢复；精确分割/变化统计需要对应处理服务继续接入。"));
        return out;
    }

    private Map<String, Object> catalogCheck(String place, String startDate, String endDate) {
        String targetPlace = place == null ? "" : place.trim();
        var match = localImageryArchiveService.findBestBandMatch(targetPlace, parseDateOrNull(startDate), parseDateOrNull(endDate));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("matched", match.isPresent());
        match.ifPresent(m -> {
            metrics.put("file", m.fileName());
            metrics.put("date", m.year() + "-" + String.format("%02d", m.month()) + "-" + String.format("%02d", m.day()));
            metrics.put("source_kind", "local-cog");
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("analysisType", "catalog_check");
        out.put("sourceKind", "local-cog");
        out.put("metrics", metrics);
        out.put("reportTitle", "本地影像目录检查报告");
        out.put("reportSummary", match
                .map(m -> "已命中本地多波段 COG：" + m.fileName() + "。")
                .orElse("未在本地影像目录中命中符合条件的多波段 COG。"));
        out.put("message", "本地影像目录检查完成");
        return out;
    }

    private static String titleFor(String type) {
        return switch (type) {
            case "composite" -> "彩色合成分析报告";
            case "threshold" -> "阈值提取分析报告";
            case "change_detection" -> "变化检测分析报告";
            default -> "遥感基础处理报告";
        };
    }

    private static String reportFor(String type, String place, String startDate, String endDate) {
        String name = place == null || place.isBlank() ? "目标区域" : place;
        return "## " + titleFor(type) + "\n\n"
                + "已为 " + name + " 选择本地优先的数据源，并返回可用于地图展示的基础影像信息。\n\n"
                + "| 项目 | 内容 |\n"
                + "|---|---|\n"
                + "| 分析类型 | " + type + " |\n"
                + "| 数据源策略 | local-cog 优先，必要时由 GEE 兜底 |\n"
                + "| 时间范围 | " + safeRange(startDate, endDate) + " |";
    }

    private static Map<String, Object> extent(LiveImageryService.LiveImageryResult imagery) {
        Map<String, Object> extent = new LinkedHashMap<>();
        extent.put("minLng", imagery.minLng());
        extent.put("minLat", imagery.minLat());
        extent.put("maxLng", imagery.maxLng());
        extent.put("maxLat", imagery.maxLat());
        return extent;
    }

    private static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeRange(String startDate, String endDate) {
        String s = startDate == null || startDate.isBlank() ? "未指定" : startDate;
        String e = endDate == null || endDate.isBlank() ? "未指定" : endDate;
        return s + " ~ " + e;
    }

    private static String extractPlace(String message) {
        if (message == null) return "";
        for (String place : List.of("南京", "太湖", "巢湖", "鄱阳湖", "苏州", "无锡", "上海", "北京")) {
            if (message.contains(place)) return place;
        }
        return "";
    }
}
