package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.LiveImageryService;
import com.nnu.rasterapi.service.LiveImagerySessionStore;
import com.nnu.rasterapi.service.LiveTileService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/live-imagery")
@CrossOrigin(origins = "*")
public class LiveImageryController {
    private final LiveImageryService liveImageryService;
    private final LiveImagerySessionStore sessionStore;
    private final LiveTileService tileService;

    public LiveImageryController(
            LiveImageryService liveImageryService,
            LiveImagerySessionStore sessionStore,
            LiveTileService tileService
    ) {
        this.liveImageryService = liveImageryService;
        this.sessionStore = sessionStore;
        this.tileService = tileService;
    }

    @GetMapping("/by-place")
    public LiveImageryResponse byPlace(
            @RequestParam String place,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end
    ) {
        LiveImageryService.LiveImageryResult result = liveImageryService.queryByPlaceName(
                place,
                parseDateOrNull(start),
                parseDateOrNull(end)
        );
        String token = sessionStore.put(new LiveImagerySessionStore.LiveSession(
                java.time.Instant.now(),
                result.minLng(),
                result.minLat(),
                result.maxLng(),
                result.maxLat(),
                result.cogUrls(),
                result.footprints(),
                result.selectedScenes(),
                result.boundaries()
        ));
        return new LiveImageryResponse(
                result.query(),
                result.displayName(),
                result.minLng(),
                result.minLat(),
                result.maxLng(),
                result.maxLat(),
                token,
                "/api/live-imagery/tiles/" + token + "/{z}/{x}/{y}.png",
                result.coverageRatio(),
                result.selectedScenes(),
                result.boundaries()
        );
    }

    @GetMapping(value = "/tiles/{token}/{z}/{x}/{y}.png", produces = "image/png")
    public byte[] tile(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @org.springframework.web.bind.annotation.PathVariable int z,
            @org.springframework.web.bind.annotation.PathVariable int x,
            @org.springframework.web.bind.annotation.PathVariable int y
    ) {
        LiveImagerySessionStore.LiveSession session = sessionStore.get(token);
        if (session == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "token 不存在或已过期");
        }
        try {
            return this.tileService.renderMaskedTile(z, x, y, session.cogUrls(), session.footprints(), session.boundaries());
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "瓦片生成失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    e
            );
        }
    }

    public record LiveImageryResponse(
            String query,
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            String token,
            String tileTemplateUrl,
            double coverageRatio,
            java.util.List<LiveImageryService.SelectedScene> selectedScenes,
            java.util.List<java.util.List<LiveImageryService.LngLat>> boundaries
    ) {
    }

    private static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "日期格式错误，需为 yyyy-MM-dd"
            );
        }
    }
}
