package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.LiveImageryService;
import com.nnu.rasterapi.service.LiveImagerySessionStore;
import com.nnu.rasterapi.service.WaterAreaAnalysisService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/water-highlight")
@CrossOrigin(origins = "*")
public class WaterHighlightController {
    private final WaterAreaAnalysisService waterAreaAnalysisService;
    private final LiveImagerySessionStore sessionStore;

    public WaterHighlightController(
            WaterAreaAnalysisService waterAreaAnalysisService,
            LiveImagerySessionStore sessionStore
    ) {
        this.waterAreaAnalysisService = waterAreaAnalysisService;
        this.sessionStore = sessionStore;
    }

    @GetMapping("/by-place")
    public WaterHighlightLayerResponse byPlace(
            @RequestParam String place,
            @RequestParam int year,
            @RequestParam int month
    ) {
        LiveImageryService.LiveImageryResult result =
                waterAreaAnalysisService.queryWaterHighlightImagery(place, YearMonth.of(year, month));
        List<List<LiveImageryService.LngLat>> boundaries = result.boundaries();
        if (boundaries == null || boundaries.isEmpty()) {
            boundaries = List.of(List.of(
                    new LiveImageryService.LngLat(result.minLng(), result.minLat()),
                    new LiveImageryService.LngLat(result.maxLng(), result.minLat()),
                    new LiveImageryService.LngLat(result.maxLng(), result.maxLat()),
                    new LiveImageryService.LngLat(result.minLng(), result.maxLat()),
                    new LiveImageryService.LngLat(result.minLng(), result.minLat())
            ));
        }

        String token = sessionStore.put(new LiveImagerySessionStore.LiveSession(
                Instant.now(),
                result.minLng(),
                result.minLat(),
                result.maxLng(),
                result.maxLat(),
                result.cogUrls(),
                result.footprints(),
                result.selectedScenes(),
                boundaries
        ));
        return new WaterHighlightLayerResponse(
                result.query(),
                result.displayName(),
                result.minLng(),
                result.minLat(),
                result.maxLng(),
                result.maxLat(),
                token,
                "/api/water-highlight/tiles/" + token + "/{z}/{x}/{y}.png"
        );
    }

    @GetMapping(value = "/tiles/{token}/{z}/{x}/{y}.png", produces = "image/png")
    public byte[] tile(
            @PathVariable String token,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(defaultValue = "2f9a9f") String color,
            @RequestParam(defaultValue = "62") int alpha
    ) {
        LiveImagerySessionStore.LiveSession session = sessionStore.get(token);
        if (session == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "token 不存在或已过期"
            );
        }
        return waterAreaAnalysisService.renderWaterHighlightTile(
                z,
                x,
                y,
                session.selectedScenes(),
                session.boundaries(),
                color,
                alpha
        );
    }

    public record WaterHighlightLayerResponse(
            String query,
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            String token,
            String tileTemplateUrl
    ) {
    }
}
