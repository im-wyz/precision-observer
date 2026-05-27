package com.nnu.rasterapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiCommandService {
    private static final Pattern YYYY_MM = Pattern.compile("(20\\d{2})\\s*年\\s*(1[0-2]|0?[1-9])\\s*月");
    private static final Pattern MM_ONLY = Pattern.compile("(^|\\D)(1[0-2]|0?[1-9])\\s*月");
    private static final Pattern PLACE_TOKEN = Pattern.compile("([\\u4e00-\\u9fa5]{2,20})(市|区|县)?");

    private final LiveImageryService liveImageryService;
    private final GreeningAnalysisService greeningAnalysisService;

    public AiCommandService(
            LiveImageryService liveImageryService,
            GreeningAnalysisService greeningAnalysisService
    ) {
        this.liveImageryService = liveImageryService;
        this.greeningAnalysisService = greeningAnalysisService;
    }

    public AiCommandResult execute(String command) {
        String text = command == null ? "" : command.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指令不能为空");
        }

        if (isGreeningChangeIntent(text)) {
            return executeGreeningChange(text);
        }
        return executeLoadImagery(text);
    }

    private AiCommandResult executeLoadImagery(String text) {
        ParsedCommon common = parseCommon(text);
        if (common.place == null || common.place.isBlank()) {
            return AiCommandResult.ask(
                    "请告诉我要分析的行政区，例如“南京”或“南京市”。",
                    "missing_place"
            );
        }
        if (common.month == null) {
            return AiCommandResult.ask(
                    "你要几月的影像？例如“2024年6月”或“6月”。",
                    "missing_month"
            );
        }
        LocalDate start = common.month.atDay(1);
        LocalDate end = common.month.atEndOfMonth();
        LiveImageryService.LiveImageryResult result = liveImageryService.queryByPlaceName(common.place, start, end);
        return AiCommandResult.imagery(
                "已为你加载 " + common.place + " " + common.month.getYear() + "年" + common.month.getMonthValue() + "月 影像。",
                result
        );
    }

    private AiCommandResult executeGreeningChange(String text) {
        ParsedCommon common = parseCommon(text);
        YearMonth[] range = parseGreeningRange(text, common.month);
        if (common.place == null || common.place.isBlank()) {
            return AiCommandResult.ask("请先告诉我要对比的行政区，例如“南京”。", "missing_place");
        }
        if (range[0] == null || range[1] == null) {
            return AiCommandResult.ask("请给出对比时间，例如“2017年3月到2018年3月”。", "missing_range");
        }

        double threshold = parseThreshold(text);
        GreeningAnalysisService.GreeningChangeResult analysis = greeningAnalysisService.analyze(
                common.place, range[0], range[1], threshold
        );
        String msg = "已完成绿化变化分析："
                + String.format("基期 %.2f km²，目标期 %.2f km²，变化 %.2f km²。",
                analysis.baseline().greenAreaM2() / 1_000_000.0,
                analysis.target().greenAreaM2() / 1_000_000.0,
                analysis.deltaGreenAreaM2() / 1_000_000.0
        );
        return AiCommandResult.greeningResult(msg, range[0], range[1], analysis);
    }

    private static boolean isGreeningChangeIntent(String text) {
        return text.contains("绿化") || text.contains("植被") || text.contains("变化") || text.contains("对比");
    }

    private static ParsedCommon parseCommon(String text) {
        YearMonth month = parseMonth(text);
        String place = parsePlace(text);
        return new ParsedCommon(place, month);
    }

    private static String parsePlace(String text) {
        String cleaned = text
                .replaceAll("[，。,.!?！？]", " ")
                .replaceAll("(分析|比较|对比|变化|绿化面积|绿化|植被|影像|遥感|加载|查看|我要|请|帮我|从|到|之间|和)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Matcher m = PLACE_TOKEN.matcher(cleaned);
        if (m.find()) {
            String token = m.group(1);
            if (token != null && token.length() >= 2) return token;
        }
        return null;
    }

    private static YearMonth parseMonth(String text) {
        Matcher m = YYYY_MM.matcher(text);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            return YearMonth.of(y, mo);
        }
        Matcher m2 = MM_ONLY.matcher(text);
        if (m2.find()) {
            int mo = Integer.parseInt(m2.group(2));
            int y = LocalDate.now().getYear();
            return YearMonth.of(y, mo);
        }
        return null;
    }

    private static YearMonth[] parseGreeningRange(String text, YearMonth fallbackSingle) {
        Matcher m = YYYY_MM.matcher(text);
        YearMonth first = null;
        YearMonth second = null;
        if (m.find()) {
            first = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (m.find()) {
                second = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        if (first != null && second != null) {
            return new YearMonth[]{first, second};
        }
        if (fallbackSingle != null) {
            return new YearMonth[]{fallbackSingle, fallbackSingle};
        }
        return new YearMonth[]{null, null};
    }

    private static double parseThreshold(String text) {
        Matcher m = Pattern.compile("NDVI\\s*([><=])\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            return Double.parseDouble(m.group(2));
        }
        return 0.3;
    }

    private record ParsedCommon(String place, YearMonth month) {
    }

    public record AiCommandResult(
            String status,
            String action,
            String message,
            String pendingField,
            Integer baselineYear,
            Integer baselineMonth,
            Integer targetYear,
            Integer targetMonth,
            LiveImageryService.LiveImageryResult imagery,
            LiveImageryService.LiveImageryResult baseline,
            LiveImageryService.LiveImageryResult target,
            GreeningAnalysisService.GreeningChangeResult greeningChange
    ) {
        public static AiCommandResult ask(String message, String pendingField) {
            return new AiCommandResult("need_more_info", "ask", message, pendingField,
                    null, null, null, null, null, null, null, null);
        }

        public static AiCommandResult imagery(String message, LiveImageryService.LiveImageryResult imagery) {
            return new AiCommandResult("ok", "load_imagery", message, null,
                    null, null, null, null, imagery, null, null, null);
        }

        public static AiCommandResult greeningResult(
                String message,
                YearMonth baselineYm,
                YearMonth targetYm,
                GreeningAnalysisService.GreeningChangeResult result
        ) {
            return new AiCommandResult("ok", "greening_change", message, null,
                    baselineYm.getYear(), baselineYm.getMonthValue(),
                    targetYm.getYear(), targetYm.getMonthValue(),
                    null, null, null, result);
        }
    }
}

