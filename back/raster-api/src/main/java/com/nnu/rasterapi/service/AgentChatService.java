package com.nnu.rasterapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 AI 代理服务（规则版）。
 * <p>
 * 说明：
 * - 当前先实现“耕地面积变化”场景。
 * - 耕地面积由 CroplandAnalysisService 基于 STAC + TiTiler 瓦片计算。
 * - 返回 answer + echarts option，前端可直接展示。
 */
@Service
public class AgentChatService {
    private final LlmIntentTool llmIntentTool;
    private final CroplandAnalysisService croplandAnalysisService;

    public AgentChatService(LlmIntentTool llmIntentTool, CroplandAnalysisService croplandAnalysisService) {
        this.llmIntentTool = llmIntentTool;
        this.croplandAnalysisService = croplandAnalysisService;
    }

    /**
     * 处理用户自然语言。
     */
    public AgentChatResponse handle(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }

        LlmIntentTool.ParsedIntent intent = llmIntentTool.parseIntent(text);

        if (!"cropland_change".equalsIgnoreCase(intent.intent())) {
            return AgentChatResponse.of(
                    "当前已支持“耕地面积变化”分析。你可以这样说：分析2017年3月到2018年3月南京耕地面积变化。",
                    "unsupported_intent",
                    null,
                    null
            );
        }

        if (intent.place() == null || intent.place().isBlank()) {
            return AgentChatResponse.of("请先告诉我要分析的地区，例如：南京。", "missing_place", null, null);
        }
        if (intent.startMonth() == null || intent.endMonth() == null) {
            return AgentChatResponse.of("请给出两个时间点，例如：2017年3月到2018年3月。", "missing_time_range", null, null);
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

        return AgentChatResponse.of(answer, "cropland_change", buildCroplandChartOption(result), buildData(result));
    }

    /**
     * 构造 ECharts option。
     * 前端拿到后直接 setOption 即可渲染柱状图。
     */
    private Map<String, Object> buildCroplandChartOption(CroplandAnalysisService.CroplandChangeResult result) {
        Map<String, Object> option = new LinkedHashMap<>();

        option.put("title", Map.of(
                "text", result.place() + " 耕地面积变化",
                "left", "center"
        ));
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of(
                "type", "category",
                "data", List.of(
                        formatYm(result.startYear(), result.startMonth()),
                        formatYm(result.endYear(), result.endMonth())
                )
        ));
        option.put("yAxis", Map.of(
                "type", "value",
                "name", "面积 (km²)"
        ));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", "耕地面积");
        series.put("type", "bar");
        series.put("barWidth", "40%");
        series.put("data", List.of(result.start().croplandAreaKm2(), result.end().croplandAreaKm2()));
        series.put("itemStyle", Map.of("color", "#4F46E5"));
        option.put("series", List.of(series));

        option.put("grid", Map.of("left", "8%", "right", "6%", "bottom", "12%", "containLabel", true));
        return option;
    }

    private static String formatYm(int year, int month) {
        return year + "-" + String.format("%02d", month);
    }

    /**
     * 双期为 0 时区分「无有效 NDVI 像素」（多为 TiTiler/瓦片问题）与「有像素但 NDVI 未落在阈值内」。
     */
    private static String croplandZeroHint(CroplandAnalysisService.CroplandChangeResult result) {
        if (result.start().croplandAreaKm2() > 0 || result.end().croplandAreaKm2() > 0) {
            return "";
        }
        double sAdmin = result.start().adminAreaKm2();
        double eAdmin = result.end().adminAreaKm2();
        if (sAdmin <= 1e-6 && eAdmin <= 1e-6) {
            return " 说明：两期均未统计到有效 NDVI 像素（常见为 TiTiler 不可达或 live.titiler.base-url 配置不对，瓦片请求失败被静默忽略；STAC 检索仍会完成故整体耗时可能不长）。请确认 TiTiler 进程可用且后端能访问该地址。";
        }
        return " 说明：行政区内有 NDVI 像素，但落在当前耕地 NDVI 区间 [analysis.cropland.ndvi-min, ndvi-max] 内的面积为 0（例如冬季裸地 NDVI 偏低、或城区占比高）。可在 application.properties 中适当放宽阈值后重试。";
    }

    private Map<String, Object> buildData(CroplandAnalysisService.CroplandChangeResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("place", result.place());
        data.put("startYear", result.startYear());
        data.put("startMonth", result.startMonth());
        data.put("endYear", result.endYear());
        data.put("endMonth", result.endMonth());
        data.put("startAreaKm2", result.start().croplandAreaKm2());
        data.put("endAreaKm2", result.end().croplandAreaKm2());
        data.put("deltaAreaKm2", result.deltaAreaKm2());
        data.put("deltaPercent", result.deltaPercent());
        data.put("coverageStart", result.start().coverageRatio());
        data.put("coverageEnd", result.end().coverageRatio());
        return data;
    }

    /**
     * 对外返回对象。
     */
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

