package com.nnu.rasterapi.repository;

import com.nnu.rasterapi.entity.RasterMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RasterRepository extends JpaRepository<RasterMetadata, Long> {
    @Query(
            value = "SELECT * FROM raster_metadata WHERE ST_Intersects(geom, ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326))",
            nativeQuery = true
    )
    List<RasterMetadata> findIntersectingRasters(
            @Param("minLng") double minLng,
            @Param("minLat") double minLat,
            @Param("maxLng") double maxLng,
            @Param("maxLat") double maxLat
    );
}
