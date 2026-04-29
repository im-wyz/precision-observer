package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.entity.RasterMetadata;
import com.nnu.rasterapi.repository.RasterRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rasters")
@CrossOrigin(origins = "*")
public class RasterController {

    private final RasterRepository rasterRepository;

    public RasterController(RasterRepository rasterRepository) {
        this.rasterRepository = rasterRepository;
    }

    @GetMapping("/search")
    public List<RasterMetadata> search(
            @RequestParam double minLng,
            @RequestParam double minLat,
            @RequestParam double maxLng,
            @RequestParam double maxLat
    ) {
        return rasterRepository.findIntersectingRasters(minLng, minLat, maxLng, maxLat);
    }
}
