package com.nnu.rasterapi.dto;

import com.nnu.rasterapi.entity.Task;

import java.time.Instant;
import java.util.List;

/**
 * 任务查询响应。
 */
public record TaskResponse(
        String id,
        String message,
        List<Object> regionCoords,
        String startDate,
        String endDate,
        String status,
        String answer,
        String cogPath,
        String downloadUrl,
        String errorMessage,
        String currentNode,
        String lastMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskResponse fromEntity(Task task, List<Object> regionCoordsParsed, String currentNode, String lastMessage) {
        return new TaskResponse(
                task.getId(),
                task.getMessage(),
                regionCoordsParsed,
                task.getStartDate() != null ? task.getStartDate().toString() : null,
                task.getEndDate() != null ? task.getEndDate().toString() : null,
                task.getStatus(),
                task.getAnswer(),
                task.getCogPath(),
                task.getDownloadUrl(),
                task.getErrorMessage(),
                currentNode,
                lastMessage,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
