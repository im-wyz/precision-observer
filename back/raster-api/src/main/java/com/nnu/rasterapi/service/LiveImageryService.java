package com.nnu.rasterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.CoordinateSequence;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LiveImageryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final HttpClient httpClient;
    private final String amapGeocodeUrl;
    private final String amapDistrictUrl;
    private final String amapKey;
    private final String stacSearchUrl;
    private final Duration timeout;
    private final double minCoverageRatio;

    public LiveImageryService(
            @Value("${live.amap.geocode-url:https://restapi.amap.com/v3/geocode/geo}") String amapGeocodeUrl,
            @Value("${live.amap.district-url:https://restapi.amap.com/v3/config/district}") String amapDistrictUrl,
            @Value("${live.amap.key:}") String amapKey,
            @Value("${live.stac.search-url:https://earth-search.aws.element84.com/v1/search}") String stacSearchUrl,
            @Value("${live.http-timeout-ms:12000}") long timeoutMs,
            @Value("${live.coverage-min-ratio:0.995}") double minCoverageRatio
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        this.amapGeocodeUrl = amapGeocodeUrl;
        this.amapDistrictUrl = amapDistrictUrl;
        this.amapKey = amapKey;
        this.stacSearchUrl = stacSearchUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.minCoverageRatio = Math.max(0.0, Math.min(1.0, minCoverageRatio));
    }

    public LiveImageryResult queryByPlaceName(String placeName, LocalDate startDate, LocalDate endDate) {
        String query = placeName == null ? "" : placeName.trim();
        if (query.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地名不能为空");
        }
        if (amapKey == null || amapKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "高德 API Key 未配置");
        }

        List<List<LngLat>> boundaries = districtBoundaryByAmap(query);
        Bbox geocodeBbox = geocodeByAmap(query);
        // 关键：影像检索范围以“行政区边界”外包框为准（而不是地名 geocode 的小框）
        Bbox searchBbox = computeBboxFromBoundaries(geocodeBbox.displayName, boundaries);
        if (searchBbox == null) {
            searchBbox = geocodeBbox;
        }

        Geometry admin = boundariesToMultiPolygon(boundaries);
        if (admin == null || admin.isEmpty()) {
            admin = bboxToPolygon(searchBbox);
        } else {
            admin = fixGeometry(admin);
        }

        FootprintSelection selection = searchCogsByStac(searchBbox, admin, startDate, endDate);
        double coverageRatio = computeCoverageRatio(admin, selection.selectedCandidates);

        return new LiveImageryResult(
                query,
                geocodeBbox.displayName,
                searchBbox.minLng,
                searchBbox.minLat,
                searchBbox.maxLng,
                searchBbox.maxLat,
                selection.cogUrls,
                selection.footprints,
                selection.scenes,
                coverageRatio,
                boundaries
        );
    }

    private Bbox geocodeByAmap(String query) {
        String url = amapGeocodeUrl +
                "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8) +
                "&output=JSON" +
                "&address=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        // 高德偶发 ENGINE_RESPONSE_DATA_ERROR，做小次数重试
        int attempts = 3;
        long backoffMs = 250;
        for (int i = 1; i <= attempts; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header("User-Agent", "precision-observer-raster-api/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码调用失败: " + response.statusCode());
                }

                JsonNode root = OBJECT_MAPPER.readTree(response.body());
                String status = root.path("status").asText();
                String info = root.path("info").asText("");
                String infoCode = root.path("infocode").asText("");
                if (!"1".equals(status)) {
                    // 对可恢复的引擎错误重试
                    if ("ENGINE_RESPONSE_DATA_ERROR".equalsIgnoreCase(info) && i < attempts) {
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码请求被中断", ie);
                        }
                        backoffMs *= 2;
                        continue;
                    }
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "高德地理编码失败: " + (info.isBlank() ? "未知错误" : info) + (infoCode.isBlank() ? "" : ("(" + infoCode + ")"))
                    );
                }

                JsonNode geocodes = root.path("geocodes");
                if (!geocodes.isArray() || geocodes.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到匹配地名");
                }
                JsonNode first = geocodes.get(0);
                String rectangle = first.path("rectangle").asText("");
                String formattedAddress = first.path("formatted_address").asText(query);
                double minLng;
                double minLat;
                double maxLng;
                double maxLat;

                if (!rectangle.isBlank() && rectangle.contains(";")) {
                    String[] corners = rectangle.split(";");
                    String[] sw = corners[0].split(",");
                    String[] ne = corners[1].split(",");
                    if (sw.length < 2 || ne.length < 2) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德边界格式错误");
                    }
                    double swLng = Double.parseDouble(sw[0]);
                    double swLat = Double.parseDouble(sw[1]);
                    double neLng = Double.parseDouble(ne[0]);
                    double neLat = Double.parseDouble(ne[1]);
                    // AMap is GCJ-02 -> convert to WGS84
                    Gcj02Wgs84.LngLat swWgs = Gcj02Wgs84.gcj02ToWgs84(swLng, swLat);
                    Gcj02Wgs84.LngLat neWgs = Gcj02Wgs84.gcj02ToWgs84(neLng, neLat);
                    minLng = Math.min(swWgs.lng(), neWgs.lng());
                    minLat = Math.min(swWgs.lat(), neWgs.lat());
                    maxLng = Math.max(swWgs.lng(), neWgs.lng());
                    maxLat = Math.max(swWgs.lat(), neWgs.lat());
                } else {
                    String location = first.path("location").asText("");
                    if (location.isBlank() || !location.contains(",")) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码返回边界与点位均为空");
                    }
                    String[] lonLat = location.split(",");
                    if (lonLat.length < 2) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德 location 格式错误");
                    }
                    double lng = Double.parseDouble(lonLat[0]);
                    double lat = Double.parseDouble(lonLat[1]);
                    // AMap is GCJ-02 -> convert to WGS84
                    Gcj02Wgs84.LngLat center = Gcj02Wgs84.gcj02ToWgs84(lng, lat);
                    double delta = 0.35;
                    minLng = center.lng() - delta;
                    minLat = center.lat() - delta;
                    maxLng = center.lng() + delta;
                    maxLat = center.lat() + delta;
                }
                return new Bbox(formattedAddress, minLng, minLat, maxLng, maxLat);
            } catch (ResponseStatusException e) {
                // 非引擎错误直接抛
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码请求被中断", e);
            } catch (IOException | NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码解析失败", e);
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码失败");
    }

    private FootprintSelection searchCogsByStac(Bbox bbox, Geometry admin, LocalDate startDate, LocalDate endDate) {
        // 仅使用 Sentinel，避免与 Landsat 混合导致清晰度下降
        String[] collectionsToTry = {"sentinel-2-l2a", "sentinel-2-l1c"};
        double[] expandFactors = {1.0, 1.8, 3.0, 5.0};
        double centerLng = (bbox.minLng + bbox.maxLng) / 2.0;
        double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;

        ResponseStatusException lastError = null;
        Double bestCoverage = null;
        // 注意：此处不再优先“中心点 intersects”提前返回
        // 对于南京/北京这类行政区较大的情况，中心点命中的单景不一定覆盖全域边缘
        for (double factor : expandFactors) {
            Bbox searchBbox = expandBbox(bbox, factor);
            for (String collection : collectionsToTry) {
                try {
                    FootprintSelection sel = searchCogsByStacCollection(
                            searchBbox, collection, centerLng, centerLat, false, admin, startDate, endDate
                    );
                    if (sel.coverageRatio >= minCoverageRatio && !sel.cogUrls.isEmpty()) {
                        return sel;
                    }
                    if (bestCoverage == null || sel.coverageRatio > bestCoverage) {
                        bestCoverage = sel.coverageRatio;
                    }
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                        lastError = ex;
                    }
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        if (bestCoverage != null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    String.format(
                            "当前时间范围内影像无法完整覆盖行政区（最佳覆盖率 %.1f%%，要求 %.1f%%）",
                            bestCoverage * 100.0,
                            minCoverageRatio * 100.0
                    )
            );
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该区域未找到可用遥感影像");
    }

    private FootprintSelection searchCogsByStacCollection(
            Bbox bbox,
            String collection,
            double centerLng,
            double centerLat,
            boolean preferCenterPoint,
            Geometry admin,
            LocalDate startDate,
            LocalDate endDate
    ) {
        try {
            ObjectNode requestJson = OBJECT_MAPPER.createObjectNode();
            ArrayNode collections = requestJson.putArray("collections");
            collections.add(collection);
            ArrayNode bboxNode = requestJson.putArray("bbox");
            bboxNode.add(bbox.minLng).add(bbox.minLat).add(bbox.maxLng).add(bbox.maxLat);
            if (preferCenterPoint) {
                ObjectNode intersects = requestJson.putObject("intersects");
                intersects.put("type", "Point");
                ArrayNode coordinates = intersects.putArray("coordinates");
                coordinates.add(centerLng).add(centerLat);
            }
            requestJson.put("limit", 200);
            String datetimeRange = buildDatetimeRange(startDate, endDate);
            if (datetimeRange != null) {
                requestJson.put("datetime", datetimeRange);
            }
            ArrayNode sortby = requestJson.putArray("sortby");
            ObjectNode sortField = sortby.addObject();
            sortField.put("field", "properties.datetime");
            sortField.put("direction", "desc");
            String requestBody = requestJson.toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stacSearchUrl))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "STAC 检索失败(" + collection + "): " + response.statusCode()
                );
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该区域未找到可用遥感影像");
            }

            // 新要求：覆盖判定只看 STAC footprint 几何是否覆盖行政边界（不看像素有效、不看云与空洞）
            Geometry adminFixed = (admin == null || admin.isEmpty()) ? bboxToPolygon(bbox) : admin;
            adminFixed = fixGeometry(adminFixed);

            List<Candidate> candidates = buildCandidatesWithFootprint(features, adminFixed, centerLng, centerLat);
            // 需求：用最少景数完成“几何覆盖行政区”；优先同一时间段，其次再跨时间段补齐
            int maxCogs = Math.max(1, Math.min(50, candidates.size()));
            List<Candidate> selected = selectCandidatesByTimeBucketThenFootprintCover(candidates, adminFixed, maxCogs);
            if (selected.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "STAC 结果中未找到可用 COG 链接");
            }
            double coverageRatio = computeCoverageRatio(adminFixed, selected);
            List<String> urls = new ArrayList<>();
            List<List<List<LngLat>>> footprints = new ArrayList<>();
            List<SelectedScene> scenes = new ArrayList<>();
            for (Candidate c : selected) {
                urls.add(c.cogUrl);
                footprints.add(geometryToRingsLngLat(c.footprint));
                scenes.add(new SelectedScene(c.itemId, c.collection, c.datetime, c.assetKey, c.cloudCover, c.cogUrl));
            }
            return new FootprintSelection(urls, footprints, scenes, selected, coverageRatio);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "STAC 请求被中断", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "STAC 响应解析失败", e);
        }
    }

    private record Candidate(
            double score,
            String timeBucket,
            long timeBucketEpochDay,
            String itemId,
            String collection,
            String datetime,
            String assetKey,
            String cogUrl,
            double cloudCover,
            Geometry footprint
    ) {
    }

    private record FootprintSelection(
            List<String> cogUrls,
            List<List<List<LngLat>>> footprints,
            List<SelectedScene> scenes,
            List<Candidate> selectedCandidates,
            double coverageRatio
    ) {
    }

    private static List<Candidate> buildCandidatesWithFootprint(JsonNode features, Geometry admin, double centerLng, double centerLat) {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode feature : features) {
            AssetRef assetRef = extractCogAsset(feature.path("assets"));
            if (assetRef == null || assetRef.href == null || assetRef.href.isBlank()) continue;

            Geometry footprint = geoJsonToGeometry(feature.path("geometry"));
            if (footprint == null || footprint.isEmpty()) continue;
            footprint = fixGeometry(footprint);

            TimeBucket bucket = parseTimeBucket(feature.path("properties").path("datetime").asText(""));

            // 仅做候选排序用途：优先覆盖中心点 + footprint 与 admin 的交叠面积大
            boolean containsCenter = footprint.covers(GEOMETRY_FACTORY.createPoint(new Coordinate(centerLng, centerLat)));
            double overlap = safeArea(footprint.intersection(admin));
            double score = (containsCenter ? 1_000_000 : 0) + overlap;
            double cloudCover = parseCloudCover(feature.path("properties"));
            out.add(new Candidate(
                    score,
                    bucket.label,
                    bucket.epochDay,
                    feature.path("id").asText(""),
                    feature.path("collection").asText(""),
                    feature.path("properties").path("datetime").asText(""),
                    assetRef.assetKey,
                    assetRef.href,
                    cloudCover,
                    footprint
            ));
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    private record TimeBucket(String label, long epochDay) {
    }

    private static TimeBucket parseTimeBucket(String datetime) {
        // STAC datetime 通常是 ISO-8601（UTC）。按“天”分桶满足“同一时间段”的最朴素定义。
        try {
            Instant instant = Instant.parse(datetime);
            LocalDate day = instant.atZone(ZoneOffset.UTC).toLocalDate();
            return new TimeBucket(day.toString(), day.toEpochDay());
        } catch (Exception e) {
            // 缺失/异常时间：放到最旧桶，避免影响“优先最新时间段”
            return new TimeBucket("unknown", Long.MIN_VALUE);
        }
    }

    private static List<Candidate> selectCandidatesByTimeBucketThenFootprintCover(
            List<Candidate> candidates,
            Geometry admin,
            int maxCogs
    ) {
        if (candidates.isEmpty()) return List.of();

        // 按时间桶（epochDay）从新到旧遍历；每个桶内做 footprint cover 贪心，若能覆盖则立刻返回
        List<Long> buckets = new ArrayList<>();
        Set<Long> seenBuckets = new HashSet<>();
        for (Candidate c : candidates) {
            if (seenBuckets.add(c.timeBucketEpochDay)) {
                buckets.add(c.timeBucketEpochDay);
            }
        }
        buckets.sort((a, b) -> Long.compare(b, a));

        List<Candidate> bestFullSameBucket = null;
        double bestFullSameBucketCloud = Double.POSITIVE_INFINITY;
        List<Candidate> bestSoFar = List.of();
        double bestRemaining = Double.POSITIVE_INFINITY;
        double adminArea = safeArea(admin);
        double eps = adminArea * 1e-6;

        for (Long bucket : buckets) {
            List<Candidate> inBucket = new ArrayList<>();
            for (Candidate c : candidates) {
                if (c.timeBucketEpochDay == bucket) inBucket.add(c);
            }
            if (inBucket.isEmpty()) continue;
            inBucket.sort((a, b) -> Double.compare(b.score, a.score));

            List<Candidate> selected = selectCandidatesByFootprintCoverGreedy(inBucket, admin, maxCogs);
            double remaining = remainingAreaAfterSelection(admin, selected);
            if (remaining <= eps) {
                // 在同一月份候选中，优先“更低云量”，其次“更少景数”，最后“更近日期”
                double avgCloud = averageCloudCover(selected);
                if (bestFullSameBucket == null
                        || avgCloud < bestFullSameBucketCloud
                        || (Math.abs(avgCloud - bestFullSameBucketCloud) < 1e-6 && selected.size() < bestFullSameBucket.size())) {
                    bestFullSameBucket = selected;
                    bestFullSameBucketCloud = avgCloud;
                }
                continue;
            }
            if (remaining < bestRemaining) {
                bestRemaining = remaining;
                bestSoFar = selected;
            }
        }

        if (bestFullSameBucket != null) {
            return bestFullSameBucket;
        }

        // 同一时间段无法完整覆盖时，放宽到跨时间段，追求“最少景数的完整几何覆盖”
        List<Candidate> all = new ArrayList<>(candidates);
        all.sort((a, b) -> Double.compare(b.score, a.score));
        List<Candidate> crossBucket = selectCandidatesByFootprintCoverGreedy(all, admin, maxCogs);
        double crossRemaining = remainingAreaAfterSelection(admin, crossBucket);
        if (crossRemaining <= eps) {
            return crossBucket;
        }

        // 仍无法完整覆盖时，返回当前未覆盖最小方案（便于前端仍有可见结果）
        return bestSoFar.isEmpty() ? crossBucket : bestSoFar;
    }

    private static double parseCloudCover(JsonNode properties) {
        if (properties == null || properties.isMissingNode()) return 100.0;
        JsonNode eoCloud = properties.path("eo:cloud_cover");
        if (!eoCloud.isMissingNode() && eoCloud.isNumber()) {
            return clampCloud(eoCloud.asDouble());
        }
        JsonNode cloud = properties.path("cloud_cover");
        if (!cloud.isMissingNode() && cloud.isNumber()) {
            return clampCloud(cloud.asDouble());
        }
        return 100.0;
    }

    private static double clampCloud(double v) {
        if (!Double.isFinite(v)) return 100.0;
        return Math.max(0.0, Math.min(100.0, v));
    }

    private static double averageCloudCover(List<Candidate> selected) {
        if (selected == null || selected.isEmpty()) return 100.0;
        double sum = 0.0;
        int cnt = 0;
        for (Candidate c : selected) {
            sum += c.cloudCover;
            cnt++;
        }
        return cnt == 0 ? 100.0 : (sum / cnt);
    }

    private static double remainingAreaAfterSelection(Geometry admin, List<Candidate> selected) {
        if (selected == null || selected.isEmpty()) return safeArea(admin);
        try {
            Geometry union = null;
            for (Candidate c : selected) {
                union = (union == null) ? c.footprint : union.union(c.footprint);
            }
            Geometry remaining = admin.difference(union == null ? GEOMETRY_FACTORY.createGeometryCollection(null) : union);
            return safeArea(remaining);
        } catch (Exception e) {
            return safeArea(admin);
        }
    }

    private static double computeCoverageRatio(Geometry admin, List<Candidate> selected) {
        double adminArea = safeArea(admin);
        if (adminArea <= 0) return 0.0;
        try {
            Geometry union = null;
            for (Candidate c : selected) {
                union = (union == null) ? c.footprint : union.union(c.footprint);
            }
            if (union == null || union.isEmpty()) return 0.0;
            double covered = safeArea(intersectionSafe(admin, union));
            return Math.max(0.0, Math.min(1.0, covered / adminArea));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Candidate fetchBestCandidateByPoint(
            String collection,
            Bbox cityBbox,
            double lng,
            double lat,
            double centerLng,
            double centerLat
    ) {
        try {
            ObjectNode requestJson = OBJECT_MAPPER.createObjectNode();
            requestJson.putArray("collections").add(collection);
            ObjectNode intersects = requestJson.putObject("intersects");
            intersects.put("type", "Point");
            intersects.putArray("coordinates").add(lng).add(lat);
            requestJson.put("limit", 5);
            ArrayNode sortby = requestJson.putArray("sortby");
            ObjectNode sortField = sortby.addObject();
            sortField.put("field", "properties.datetime");
            sortField.put("direction", "desc");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stacSearchUrl))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;

            JsonNode features = OBJECT_MAPPER.readTree(response.body()).path("features");
            if (!features.isArray() || features.isEmpty()) return null;

            // 仅用于“点查询”的简易兜底：把 bbox 当作 admin
            Geometry admin = bboxToPolygon(cityBbox);
            List<Candidate> candidates = buildCandidatesWithFootprint(features, admin, centerLng, centerLat);
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Candidate> selectCandidatesByFootprintCoverGreedy(
            List<Candidate> candidates,
            Geometry admin,
            int maxCogs
    ) {
        if (candidates.isEmpty()) return List.of();
        double adminArea = safeArea(admin);
        if (adminArea <= 0) return List.of();

        List<Candidate> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Geometry union = null;

        // 目标：footprint 几何覆盖行政边界（允许极小数值误差）
        double eps = adminArea * 1e-6;

        while (selected.size() < maxCogs) {
            Geometry covered = union == null ? GEOMETRY_FACTORY.createGeometryCollection(null) : union;
            Geometry remaining;
            try {
                remaining = admin.difference(covered);
            } catch (Exception e) {
                remaining = admin;
            }
            if (safeArea(remaining) <= eps) break;

            double bestGain = 0.0;
            Candidate best = null;
            for (Candidate c : candidates) {
                if (seen.contains(c.cogUrl)) continue;
                Geometry fp = c.footprint;
                if (fp == null || fp.isEmpty()) continue;
                double gain = safeArea(intersectionSafe(remaining, fp));
                if (gain > bestGain) {
                    bestGain = gain;
                    best = c;
                }
            }
            if (best == null || bestGain <= eps) break;

            selected.add(best);
            seen.add(best.cogUrl);
            union = (union == null) ? best.footprint : union.union(best.footprint);
        }

        // 至少返回 1 张（便于前端可见）
        if (selected.isEmpty()) {
            selected.add(candidates.get(0));
        }
        return selected;
    }

    private static List<List<LngLat>> geometryToRingsLngLat(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) return List.of();
        List<List<LngLat>> rings = new ArrayList<>();
        if (geometry instanceof Polygon p) {
            rings.addAll(polygonToRingsLngLat(p));
        } else if (geometry instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Geometry g = mp.getGeometryN(i);
                if (g instanceof Polygon pp) {
                    rings.addAll(polygonToRingsLngLat(pp));
                }
            }
        }
        return rings;
    }

    private static List<List<LngLat>> polygonToRingsLngLat(Polygon polygon) {
        if (polygon == null || polygon.isEmpty()) return List.of();
        List<List<LngLat>> out = new ArrayList<>();
        out.add(linearRingToLngLat(polygon.getExteriorRing()));
        return out;
    }

    private static List<LngLat> linearRingToLngLat(org.locationtech.jts.geom.LineString ring) {
        if (ring == null || ring.isEmpty()) return List.of();
        CoordinateSequence seq = ring.getCoordinateSequence();
        List<LngLat> pts = new ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            pts.add(new LngLat(seq.getX(i), seq.getY(i)));
        }
        return pts;
    }

    private static double safeArea(Geometry g) {
        try {
            return g == null ? 0.0 : Math.max(0.0, g.getArea());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static Geometry fixGeometry(Geometry g) {
        if (g == null) return null;
        try {
            // 常见：自交、多段边界导致 covers/difference 不稳定，用 buffer(0) 进行拓扑修复
            Geometry fixed = g.buffer(0);
            return fixed == null ? g : fixed;
        } catch (Exception e) {
            return g;
        }
    }

    private static Geometry intersectionSafe(Geometry a, Geometry b) {
        try {
            return a.intersection(b);
        } catch (Exception e) {
            return GEOMETRY_FACTORY.createGeometryCollection(null);
        }
    }

    private static Geometry bboxToPolygon(Bbox bbox) {
        Coordinate[] shell = new Coordinate[]{
                new Coordinate(bbox.minLng, bbox.minLat),
                new Coordinate(bbox.maxLng, bbox.minLat),
                new Coordinate(bbox.maxLng, bbox.maxLat),
                new Coordinate(bbox.minLng, bbox.maxLat),
                new Coordinate(bbox.minLng, bbox.minLat)
        };
        LinearRing ring = GEOMETRY_FACTORY.createLinearRing(shell);
        return GEOMETRY_FACTORY.createPolygon(ring);
    }

    private static Geometry boundariesToMultiPolygon(List<List<LngLat>> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return null;
        List<Polygon> polys = new ArrayList<>();
        for (List<LngLat> ring : boundaries) {
            if (ring == null || ring.size() < 3) continue;
            Coordinate[] coords = new Coordinate[ring.size() + 1];
            for (int i = 0; i < ring.size(); i++) {
                LngLat p = ring.get(i);
                coords[i] = new Coordinate(p.lng(), p.lat());
            }
            coords[ring.size()] = new Coordinate(ring.get(0).lng(), ring.get(0).lat());
            LinearRing shell = GEOMETRY_FACTORY.createLinearRing(coords);
            polys.add(GEOMETRY_FACTORY.createPolygon(shell));
        }
        if (polys.isEmpty()) return null;
        if (polys.size() == 1) return polys.get(0);
        return GEOMETRY_FACTORY.createMultiPolygon(polys.toArray(new Polygon[0]));
    }

    private static Geometry geoJsonToGeometry(JsonNode geometryNode) {
        if (geometryNode == null || geometryNode.isMissingNode() || geometryNode.isNull()) return null;
        String type = geometryNode.path("type").asText("");
        JsonNode coords = geometryNode.path("coordinates");
        if (type.isBlank() || coords.isMissingNode() || coords.isNull()) return null;

        return switch (type) {
            case "Polygon" -> geoJsonPolygon(coords);
            case "MultiPolygon" -> geoJsonMultiPolygon(coords);
            default -> null;
        };
    }

    private static Geometry geoJsonPolygon(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.isEmpty()) return null;
        // coordinates: [ [ [lon,lat], ... ] (shell), [ ... ] (holes)... ]
        LinearRing shell = geoJsonLinearRing(coordinates.get(0));
        if (shell == null) return null;

        List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < coordinates.size(); i++) {
            LinearRing hole = geoJsonLinearRing(coordinates.get(i));
            if (hole != null) holes.add(hole);
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    private static Geometry geoJsonMultiPolygon(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.isEmpty()) return null;
        List<Polygon> polys = new ArrayList<>();
        for (JsonNode polyNode : coordinates) {
            Geometry g = geoJsonPolygon(polyNode);
            if (g instanceof Polygon p && !p.isEmpty()) polys.add(p);
        }
        if (polys.isEmpty()) return null;
        return GEOMETRY_FACTORY.createMultiPolygon(polys.toArray(new Polygon[0]));
    }

    private static LinearRing geoJsonLinearRing(JsonNode ringNode) {
        if (ringNode == null || !ringNode.isArray() || ringNode.size() < 3) return null;
        List<Coordinate> coords = new ArrayList<>();
        for (JsonNode pt : ringNode) {
            if (!pt.isArray() || pt.size() < 2) continue;
            coords.add(new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble()));
        }
        if (coords.size() < 3) return null;
        // ensure closed
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            coords.add(new Coordinate(first.x, first.y));
        }
        return GEOMETRY_FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private static Bbox expandBbox(Bbox bbox, double factor) {
        if (factor <= 1.0) return bbox;
        double centerLng = (bbox.minLng + bbox.maxLng) / 2.0;
        double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
        double halfWidth = ((bbox.maxLng - bbox.minLng) / 2.0) * factor;
        double halfHeight = ((bbox.maxLat - bbox.minLat) / 2.0) * factor;
        double minLng = Math.max(-180.0, centerLng - halfWidth);
        double minLat = Math.max(-85.0, centerLat - halfHeight);
        double maxLng = Math.min(180.0, centerLng + halfWidth);
        double maxLat = Math.min(85.0, centerLat + halfHeight);
        return new Bbox(bbox.displayName, minLng, minLat, maxLng, maxLat);
    }

    private record AssetRef(String assetKey, String href) {
    }

    private static AssetRef extractCogAsset(JsonNode assets) {
        if (assets == null || !assets.isObject()) return null;

        // 分析优先：尽量避免 preview 资产
        String[] preferred = {"visual", "true_color"};
        for (String key : preferred) {
            JsonNode href = assets.path(key).path("href");
            if (!href.isMissingNode() && !href.asText().isBlank()) {
                return new AssetRef(key, href.asText());
            }
        }
        JsonNode fallback = assets.path("rendered_preview").path("href");
        if (!fallback.isMissingNode() && !fallback.asText().isBlank()) {
            return new AssetRef("rendered_preview", fallback.asText());
        }
        return null;
    }

    private static String buildDatetimeRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) return null;
        LocalDate s = startDate == null ? LocalDate.of(2017, 1, 1) : startDate;
        LocalDate e = endDate == null ? LocalDate.now(ZoneOffset.UTC) : endDate;
        if (e.isBefore(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");
        }
        return s + "T00:00:00Z/" + e + "T23:59:59Z";
    }

    private List<List<LngLat>> districtBoundaryByAmap(String query) {
        try {
            String url = amapDistrictUrl +
                    "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8) +
                    "&keywords=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
                    "&subdistrict=0&extensions=all";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode districts = root.path("districts");
            if (!districts.isArray() || districts.isEmpty()) {
                return List.of();
            }
            String polyline = districts.get(0).path("polyline").asText("");
            if (polyline.isBlank()) {
                return List.of();
            }

            List<List<LngLat>> polygons = new ArrayList<>();
            String[] rings = polyline.split("\\|");
            for (String ring : rings) {
                if (ring == null || ring.isBlank()) continue;
                String[] points = ring.split(";");
                List<LngLat> coords = new ArrayList<>();
                for (String point : points) {
                    if (point == null || point.isBlank() || !point.contains(",")) continue;
                    String[] parts = point.split(",");
                    if (parts.length < 2) continue;
                    try {
                        double lng = Double.parseDouble(parts[0]);
                        double lat = Double.parseDouble(parts[1]);
                        // AMap is GCJ-02 -> convert to WGS84 for Cesium/TiTiler alignment
                        Gcj02Wgs84.LngLat wgs = Gcj02Wgs84.gcj02ToWgs84(lng, lat);
                        coords.add(new LngLat(wgs.lng(), wgs.lat()));
                    } catch (NumberFormatException ignored) {
                        // skip invalid point
                    }
                }
                if (coords.size() >= 3) {
                    polygons.add(coords);
                }
            }
            return polygons;
        } catch (Exception ignored) {
            // 边界获取失败不阻断主流程，前端可退化为 bbox 显示
            return List.of();
        }
    }

    private static Bbox computeBboxFromBoundaries(String displayName, List<List<LngLat>> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return null;
        Double minLng = null, minLat = null, maxLng = null, maxLat = null;
        for (List<LngLat> ring : boundaries) {
            if (ring == null) continue;
            for (LngLat p : ring) {
                if (p == null) continue;
                double lng = p.lng;
                double lat = p.lat;
                if (!Double.isFinite(lng) || !Double.isFinite(lat)) continue;
                if (minLng == null) {
                    minLng = maxLng = lng;
                    minLat = maxLat = lat;
                } else {
                    minLng = Math.min(minLng, lng);
                    maxLng = Math.max(maxLng, lng);
                    minLat = Math.min(minLat, lat);
                    maxLat = Math.max(maxLat, lat);
                }
            }
        }
        if (minLng == null) return null;
        return new Bbox(displayName, minLng, minLat, maxLng, maxLat);
    }

    private record Bbox(String displayName, double minLng, double minLat, double maxLng, double maxLat) {
    }

    public record LiveImageryResult(
            String query,
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            List<String> cogUrls,
            List<List<List<LngLat>>> footprints,
            List<SelectedScene> selectedScenes,
            double coverageRatio,
            List<List<LngLat>> boundaries
    ) {
    }

    public record SelectedScene(
            String itemId,
            String collection,
            String datetime,
            String assetKey,
            double cloudCover,
            String cogUrl
    ) {
    }

    public record LngLat(double lng, double lat) {
    }
}
