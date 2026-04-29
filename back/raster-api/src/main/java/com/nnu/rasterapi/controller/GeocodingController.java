package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.GeocodingService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocode")
@CrossOrigin(origins = "*")
public class GeocodingController {
    private final GeocodingService geocodingService;

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @GetMapping("/city")
    public GeocodeResponse geocodeCity(@RequestParam String name) {
        GeocodingService.CityGeocodeResult result = geocodingService.geocodeCity(name);
        return new GeocodeResponse(
                result.query(),
                result.displayName(),
                result.minLng(),
                result.minLat(),
                result.maxLng(),
                result.maxLat()
        );
    }

    public record GeocodeResponse(
            String query,
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat
    ) {
    }
}
