package com.nnu.rasterapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 遥感分析任务（PostgreSQL），与 Python LangGraph 工作流通过 task_id 关联。
 */
@Getter
@Setter
@Entity
@Table(name = "analysis_task")
public class Task {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** JSON：区域坐标 bbox 或多边形 */
    @Column(name = "region_coords", columnDefinition = "TEXT")
    private String regionCoords;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(name = "cog_path", length = 1024)
    private String cogPath;

    @Column(name = "download_url", length = 2048)
    private String downloadUrl;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
