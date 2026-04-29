package com.nnu.rasterapi.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.sql.Timestamp;

@Data
@Entity
@Table(name = "raster_metadata")
public class RasterMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String cogUrl;

    private Timestamp captureTime;

    private Float cloudCover;

    // 这里先按字符串处理，后续可切换到 PostGIS Geometry 类型
    private String geom;
}
