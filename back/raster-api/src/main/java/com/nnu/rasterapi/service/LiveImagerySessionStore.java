package com.nnu.rasterapi.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LiveImagerySessionStore {
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();

    public String put(LiveSession session) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, session);
        return token;
    }

    public LiveSession get(String token) {
        return sessions.get(token);
    }

    public record LiveSession(
            Instant createdAt,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            java.util.List<String> cogUrls,
            java.util.List<java.util.List<java.util.List<LiveImageryService.LngLat>>> footprints,
            java.util.List<LiveImageryService.SelectedScene> selectedScenes,
            java.util.List<java.util.List<LiveImageryService.LngLat>> boundaries
    ) {
    }
}

