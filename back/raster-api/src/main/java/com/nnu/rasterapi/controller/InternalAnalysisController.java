package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.RemoteSensingBasicService;
import com.nnu.rasterapi.service.SpectralIndexAnalysisService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/analysis")
@CrossOrigin(origins = "*")
public class InternalAnalysisController {
    private final SpectralIndexAnalysisService spectralIndexAnalysisService;
    private final RemoteSensingBasicService remoteSensingBasicService;

    public InternalAnalysisController(
            SpectralIndexAnalysisService spectralIndexAnalysisService,
            RemoteSensingBasicService remoteSensingBasicService
    ) {
        this.spectralIndexAnalysisService = spectralIndexAnalysisService;
        this.remoteSensingBasicService = remoteSensingBasicService;
    }

    @PostMapping("/index")
    public Map<String, Object> index(@RequestBody IndexRequest request) {
        return spectralIndexAnalysisService.analyze(
                value(request.message()),
                value(request.place()),
                value(request.startDate()),
                value(request.endDate()),
                value(request.indexKey()),
                request.preferLocal() == null || request.preferLocal()
        );
    }

    @PostMapping("/basic")
    public Map<String, Object> basic(@RequestBody BasicRequest request) {
        return remoteSensingBasicService.analyze(
                value(request.message()),
                value(request.place()),
                value(request.startDate()),
                value(request.endDate()),
                value(request.compareStartDate()),
                value(request.compareEndDate()),
                value(request.analysisType()),
                request.preferLocal() == null || request.preferLocal()
        );
    }

    private static String value(String s) {
        return s == null ? "" : s.trim();
    }

    public record IndexRequest(
            String message,
            String place,
            String startDate,
            String endDate,
            String indexKey,
            Boolean preferLocal
    ) {
    }

    public record BasicRequest(
            String message,
            String place,
            String startDate,
            String endDate,
            String compareStartDate,
            String compareEndDate,
            String analysisType,
            Boolean preferLocal
    ) {
    }
}
