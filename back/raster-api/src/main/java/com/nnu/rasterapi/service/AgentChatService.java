package com.nnu.rasterapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentChatService {
    private static final String CROPLAND_CHANGE = "cropland_change";
    private static final String WATER_AREA_CHANGE = "water_area_change";

    private final LlmIntentTool llmIntentTool;
    private final CroplandAnalysisService croplandAnalysisService;
    private final WaterAreaAnalysisService waterAreaAnalysisService;

    public AgentChatService(
            LlmIntentTool llmIntentTool,
            CroplandAnalysisService croplandAnalysisService,
            WaterAreaAnalysisService waterAreaAnalysisService
    ) {
        this.llmIntentTool = llmIntentTool;
        this.croplandAnalysisService = croplandAnalysisService;
        this.waterAreaAnalysisService = waterAreaAnalysisService;
    }

    public AgentChatResponse handle(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }

        LlmIntentTool.ParsedIntent intent = llmIntentTool.parseIntent(text);
        if (!CROPLAND_CHANGE.equalsIgnoreCase(intent.intent()) && !WATER_AREA_CHANGE.equalsIgnoreCase(intent.intent())) {
            return AgentChatResponse.of(
                    "当前支持“耕地面积变化”和“水体面积变化”两期对比分析。例如：分析2024年5月到2024年8月太湖水体面积变化。",
                    "unsupported_intent",
                    null,
                    null
            );
        }
        if (intent.place() == null || intent.place().isBlank()) {
            return AgentChatResponse.of("请先告诉我要分析的地区，例如：南京、太湖。", "missing_place", null, null);
        }
        if (intent.startMonth() == null || intent.endMonth() == null) {
            return AgentChatResponse.of("请给出两个时间点，例如：2024年5月到2024年8月。", "missing_time_range", null, null);
        }

        if (WATER_AREA_CHANGE.equalsIgnoreCase(intent.intent())) {
            WaterAreaAnalysisService.WaterAreaChangeResult result = waterAreaAnalysisService.analyze(
                    intent.place(),
                    intent.startMonth(),
                    intent.endMonth()
            );
            String answer = String.format(
                    "%s 水体面积变化分析结果：%s 为 %.2f km²，%s 为 %.2f km²，变化 %.2f km²（%+.2f%%）。",
                    result.place(),
                    formatYm(result.startYear(), result.startMonth()), result.start().waterAreaKm2(),
                    formatYm(result.endYear(), result.endMonth()), result.end().waterAreaKm2(),
                    result.deltaAreaKm2(),
                    result.deltaPercent()
            );
            answer += waterZeroHint(result);
            return AgentChatResponse.of(answer, WATER_AREA_CHANGE, buildWaterChartOption(result), buildWaterData(result));
        }

        CroplandAnalysisService.CroplandChangeResult result = croplandAnalysisService.analyze(
                intent.place(),
                intent.startMonth(),
                intent.endMonth()
        );

        String answer = String.format(
                "%s 耕地面积变化分析结果：%s 为 %.2f km²，%s 为 %.2f km²，变化 %.2f km²（%+.2f%%）。",
                result.place(),
                formatYm(result.startYear(), result.startMonth()), result.start().croplandAreaKm2(),
                formatYm(result.endYear(), result.endMonth()), result.end().croplandAreaKm2(),
                result.deltaAreaKm2(),
                result.deltaPercent()
        );
        answer += croplandZeroHint(result);

        return AgentChatResponse.of(answer, CROPLAND_CHANGE, buildCroplandChartOption(result), buildCroplandData(result));
    }

    private Map<String, Object> buildCroplandChartOption(CroplandAnalysisService.CroplandChangeResult result) {
        return buildAreaChartOption(
                result.place() + " 耕地面积变化",
                "耕地面积",
                List.of(result.start().croplandAreaKm2(), result.end().croplandAreaKm2()),
                formatYm(result.startYear(), result.startMonth()),
                formatYm(result.endYear(), result.endMonth()),
                "#4F46E5"
        );
    }

    private Map<String, Object> buildWaterChartOption(WaterAreaAnalysisService.WaterAreaChangeResult result) {
        return buildAreaChartOption(
                result.place() + " 水体面积变化",
                "水体面积",
                List.of(result.start().waterAreaKm2(), result.end().waterAreaKm2()),
                formatYm(result.startYear(), result.startMonth()),
                formatYm(result.endYear(), result.endMonth()),
                "#0EA5E9"
        );
    }

    private Map<String, Object> buildAreaChartOption(
            String title,
            String seriesName,
            List<Double> data,
            String startLabel,
            String endLabel,
            String color
    ) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", title, "left", "center"));
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", List.of(startLabel, endLabel)));
        option.put("yAxis", Map.of("type", "value", "name", "面积 (km²)"));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", seriesName);
        series.put("type", "bar");
        series.put("barWidth", "40%");
        series.put("data", data);
        series.put("itemStyle", Map.of("color", color));
        option.put("series", List.of(series));
        option.put("grid", Map.of("left", "8%", "right", "6%", "bottom", "12%", "containLabel", true));
        return option;
    }

    private static String formatYm(int year, int month) {
        return year + "-" + String.format("%02d", month);
    }

    private static String croplandZeroHint(CroplandAnalysisService.CroplandChangeResult result) {
        if (result.start().croplandAreaKm2() > 0 || result.end().croplandAreaKm2() > 0) {
            return "";
        }
        double sAdmin = result.start().adminAreaKm2();
        double eAdmin = result.end().adminAreaKm2();
        if (sAdmin <= 1e-6 && eAdmin <= 1e-6) {
            return " 说明：两期均未统计到有效 NDVI 像素，请确认 TiTiler 可用、波段影像可访问。";
        }
        return " 说明：行政区内有有效像素，但落在当前耕地 NDVI 阈值区间内的面积为 0。";
    }

    private static String waterZeroHint(WaterAreaAnalysisService.WaterAreaChangeResult result) {
        if (result.start().waterAreaKm2() > 0 || result.end().waterAreaKm2() > 0) {
            return "";
        }
        double sArea = result.start().analysisAreaKm2();
        double eArea = result.end().analysisAreaKm2();
        if (sArea <= 1e-6 && eArea <= 1e-6) {
            return " 说明：两期均未统计到有效 NDWI 像素，请确认 TiTiler 可用、绿光/NIR 波段影像可访问。";
        }
        return " 说明：区域内有有效像素，但 NDWI 未达到当前水体阈值。";
    }

    private Map<String, Object> buildCroplandData(CroplandAnalysisService.CroplandChangeResult result) {
        Map<String, Object> data = buildCommonData(
                "cropland",
                result.place(),
                result.startYear(),
                result.startMonth(),
                result.endYear(),
                result.endMonth(),
                result.start().croplandAreaKm2(),
                result.end().croplandAreaKm2(),
                result.deltaAreaKm2(),
                result.deltaPercent(),
                result.start().coverageRatio(),
                result.end().coverageRatio()
        );
        data.put("analysisAreaStartKm2", result.start().adminAreaKm2());
        data.put("analysisAreaEndKm2", result.end().adminAreaKm2());
        return data;
    }

    private Map<String, Object> buildWaterData(WaterAreaAnalysisService.WaterAreaChangeResult result) {
        Map<String, Object> data = buildCommonData(
                "water",
                result.place(),
                result.startYear(),
                result.startMonth(),
                result.endYear(),
                result.endMonth(),
                result.start().waterAreaKm2(),
                result.end().waterAreaKm2(),
                result.deltaAreaKm2(),
                result.deltaPercent(),
                result.start().coverageRatio(),
                result.end().coverageRatio()
        );
        data.put("ndwiMin", result.ndwiMin());
        data.put("analysisAreaStartKm2", result.start().analysisAreaKm2());
        data.put("analysisAreaEndKm2", result.end().analysisAreaKm2());
        return data;
    }

    private Map<String, Object> buildCommonData(
            String analysisKind,
            String place,
            int startYear,
            int startMonth,
            int endYear,
            int endMonth,
            double startAreaKm2,
            double endAreaKm2,
            double deltaAreaKm2,
            double deltaPercent,
            double coverageStart,
            double coverageEnd
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analysisKind", analysisKind);
        data.put("place", place);
        data.put("startYear", startYear);
        data.put("startMonth", startMonth);
        data.put("endYear", endYear);
        data.put("endMonth", endMonth);
        data.put("startAreaKm2", startAreaKm2);
        data.put("endAreaKm2", endAreaKm2);
        data.put("deltaAreaKm2", deltaAreaKm2);
        data.put("deltaPercent", deltaPercent);
        data.put("coverageStart", coverageStart);
        data.put("coverageEnd", coverageEnd);
        return data;
    }

    public record AgentChatResponse(
            String answer,
            String intent,
            Map<String, Object> chartOption,
            Map<String, Object> data
    ) {
        public static AgentChatResponse of(
                String answer,
                String intent,
                Map<String, Object> chartOption,
                Map<String, Object> data
        ) {
            return new AgentChatResponse(answer, intent, chartOption, data);
        }
    }
}
