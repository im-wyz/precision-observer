package com.nnu.rasterapi.dto;

import java.util.List;

/**
 * 创建分析任务请求体（转发至 FastAPI POST /analyze）。
 */
public record TaskCreateRequest(
        String message,
        List<Object> regionCoords,
        String startDate,
        String endDate
) {
}
