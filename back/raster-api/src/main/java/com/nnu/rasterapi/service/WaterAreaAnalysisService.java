package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;

@Service
public class WaterAreaAnalysisService {
    private static final double EARTH_RADIUS = 6378137.0;

    private final LiveImageryService liveImageryService;
    private final HttpClient httpClient;
    private final String titilerBaseUrl;
    private final int tileSize;
    private final int zoom;
    private final Duration timeout;
    private final double ndwiMin;
    private final String tileRescaleParam;

    public WaterAreaAnalysisService(
            LiveImageryService liveImageryService,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize,
            @Value("${analysis.water.zoom:10}") int zoom,
            @Value("${analysis.water.ndwi-min:0.0}") double ndwiMin,
            @Value("${analysis.water.tile-rescale:}") String tileRescale,
            @Value("${live.http-timeout-ms:12000}") long timeoutMs
    ) {
        this.liveImageryService = liveImageryService;
        this.titilerBaseUrl = titilerBaseUrl;
        this.tileSize = tileSize;
        this.zoom = zoom;
        this.ndwiMin = ndwiMin;
        String tr = tileRescale == null ? "" : tileRescale.trim();
        this.tileRescaleParam = tr.isEmpty() || !tr.contains(",") ? "" : tr;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public WaterAreaChangeResult analyze(String place, YearMonth startMonth, YearMonth endMonth) {
        if (startMonth == null || endMonth == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "璧锋鏈堜唤涓嶈兘涓虹┖");
        }

        LiveImageryService.LiveImageryResult startImagery = liveImageryService.queryByPlaceNameForBandAnalysis(
                place, startMonth.atDay(1), startMonth.atEndOfMonth()
        );
        LiveImageryService.LiveImageryResult endImagery = liveImageryService.queryByPlaceNameForBandAnalysis(
                place, endMonth.atDay(1), endMonth.atEndOfMonth()
        );

        WaterAreaStats startStats = computeStats(startImagery);
        WaterAreaStats endStats = computeStats(endImagery);
        double deltaArea = round2(endStats.waterAreaKm2 - startStats.waterAreaKm2);
        double deltaPercent = startStats.waterAreaKm2 <= 0 ? 0 : round2((deltaArea / startStats.waterAreaKm2) * 100.0);

        return new WaterAreaChangeResult(
                place,
                startMonth.getYear(),
                startMonth.getMonthValue(),
                endMonth.getYear(),
                endMonth.getMonthValue(),
                ndwiMin,
                startStats,
                endStats,
                deltaArea,
                deltaPercent
        );
    }


    public LiveImageryService.LiveImageryResult queryWaterHighlightImagery(String place, YearMonth month) {
        if (month == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "鏈堜唤涓嶈兘涓虹┖");
        }
        return liveImageryService.queryByPlaceNameForBandAnalysis(place, month.atDay(1), month.atEndOfMonth());
    }

    public byte[] renderWaterHighlightTile(
            int z,
            int x,
            int y,
            List<LiveImageryService.SelectedScene> scenes,
            List<List<LiveImageryService.LngLat>> boundaries,
            String color,
            int alpha
    ) {
        try {
            BufferedImage out = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
            if (boundaries == null || boundaries.isEmpty()) {
                return encodePng(out);
            }

            BufferedImage adminMask = buildBoundaryMask(z, x, y, boundaries);
            float[][] ndwiGrid = new float[tileSize][tileSize];
            boolean[][] hasValue = new boolean[tileSize][tileSize];
            fillNdwiGrid(z, x, y, scenes, ndwiGrid, hasValue);

            int rgb = parseCssHexColor(color, 0x2F9A9F);
            int a = Math.max(0, Math.min(140, alpha));
            int argb = (a << 24) | (rgb & 0x00FFFFFF);
            for (int py = 0; py < tileSize; py++) {
                for (int px = 0; px < tileSize; px++) {
                    int maskAlpha = (adminMask.getRGB(px, py) >>> 24) & 0xFF;
                    if (maskAlpha == 0 || !hasValue[py][px] || ndwiGrid[py][px] < ndwiMin) continue;
                    out.setRGB(px, py, argb);
                }
            }
            return encodePng(out);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "姘翠綋楂樹寒鐡︾墖鐢熸垚澶辫触", e);
        }
    }

    private WaterAreaStats computeStats(LiveImageryService.LiveImageryResult imagery) {
        List<List<LiveImageryService.LngLat>> boundaries = imagery.boundaries();
        if (boundaries == null || boundaries.isEmpty()) {
            boundaries = fallbackBoundariesFromBbox(imagery);
        }
        if (boundaries == null || boundaries.isEmpty()) {
            return new WaterAreaStats(0, 0, imagery.coverageRatio());
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (List<LiveImageryService.LngLat> ring : boundaries) {
            for (LiveImageryService.LngLat p : ring) {
                int tx = lonToTileX(p.lng(), zoom);
                int ty = latToTileY(p.lat(), zoom);
                minX = Math.min(minX, tx);
                maxX = Math.max(maxX, tx);
                minY = Math.min(minY, ty);
                maxY = Math.max(maxY, ty);
            }
        }
        if (minX > maxX || minY > maxY) {
            return new WaterAreaStats(0, 0, imagery.coverageRatio());
        }

        double totalAreaM2 = 0.0;
        double waterAreaM2 = 0.0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                BufferedImage adminMask = buildBoundaryMask(zoom, x, y, boundaries);
                if (adminMask == null) continue;

                float[][] ndwiGrid = new float[tileSize][tileSize];
                boolean[][] hasValue = new boolean[tileSize][tileSize];
                fillNdwiGrid(zoom, x, y, imagery.selectedScenes(), ndwiGrid, hasValue);

                for (int py = 0; py < tileSize; py++) {
                    double pixelArea = pixelAreaAt(zoom, y, py);
                    for (int px = 0; px < tileSize; px++) {
                        int alpha = (adminMask.getRGB(px, py) >>> 24) & 0xFF;
                        if (alpha == 0 || !hasValue[py][px]) continue;
                        totalAreaM2 += pixelArea;
                        if (ndwiGrid[py][px] >= ndwiMin) {
                            waterAreaM2 += pixelArea;
                        }
                    }
                }
            }
        }

        return new WaterAreaStats(
                round2(waterAreaM2 / 1_000_000.0),
                round2(totalAreaM2 / 1_000_000.0),
                imagery.coverageRatio()
        );
    }

    private void fillNdwiGrid(
            int z,
            int x,
            int y,
            List<LiveImageryService.SelectedScene> scenes,
            float[][] ndwiGrid,
            boolean[][] hasValue
    ) {
        if (scenes == null) return;
        for (LiveImageryService.SelectedScene scene : scenes) {
            if (scene.greenCogUrl() == null || scene.greenCogUrl().isBlank() || scene.nirCogUrl() == null || scene.nirCogUrl().isBlank()) {
                continue;
            }
            BufferedImage green = fetchBandTile(z, x, y, scene.greenCogUrl());
            BufferedImage nir = fetchBandTile(z, x, y, scene.nirCogUrl());
            if (green == null || nir == null) continue;

            boolean greenHasAlpha = green.getColorModel().hasAlpha();
            boolean nirHasAlpha = nir.getColorModel().hasAlpha();
            int h = Math.min(tileSize, Math.min(green.getHeight(), nir.getHeight()));
            int w = Math.min(tileSize, Math.min(green.getWidth(), nir.getWidth()));
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    if (hasValue[py][px]) continue;
                    int ag = (green.getRGB(px, py) >>> 24) & 0xFF;
                    int an = (nir.getRGB(px, py) >>> 24) & 0xFF;
                    if ((greenHasAlpha && ag == 0) || (nirHasAlpha && an == 0)) continue;

                    double greenVal = gray(green.getRGB(px, py));
                    double nirVal = gray(nir.getRGB(px, py));
                    double den = greenVal + nirVal;
                    if (den <= 1e-6) continue;

                    ndwiGrid[py][px] = (float) ((greenVal - nirVal) / den);
                    hasValue[py][px] = true;
                }
            }
        }
    }

    private BufferedImage fetchBandTile(int z, int x, int y, String cogUrl) {
        try {
            BandSource source = parseBandSource(cogUrl);
            String rescale = tileRescaleParam.isEmpty() ? "0,10000" : tileRescaleParam;
            String url = titilerBaseUrl.replaceAll("/$", "")
                    + "/cog/tiles/WebMercatorQuad/" + z + "/" + x + "/" + y + ".png"
                    + "?url=" + URLEncoder.encode(source.url(), StandardCharsets.UTF_8)
                    + "&tilesize=" + tileSize
                    + "&rescale=" + URLEncoder.encode(rescale, StandardCharsets.UTF_8);
            if (source.bidx() != null) {
                url += "&bidx=" + source.bidx();
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            return ImageIO.read(new ByteArrayInputStream(resp.body()));
        } catch (Exception e) {
            return null;
        }
    }

    private static BandSource parseBandSource(String raw) {
        if (raw == null) {
            return new BandSource("", null);
        }
        int marker = raw.lastIndexOf("|bidx=");
        if (marker < 0) {
            return new BandSource(raw, null);
        }
        String url = raw.substring(0, marker);
        String bidxRaw = raw.substring(marker + "|bidx=".length()).trim();
        try {
            return new BandSource(url, Integer.parseInt(bidxRaw));
        } catch (NumberFormatException ignored) {
            return new BandSource(url, null);
        }
    }

    private BufferedImage buildBoundaryMask(int z, int x, int y, List<List<LiveImageryService.LngLat>> boundaries) {
        BufferedImage mask = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setColor(new Color(255, 255, 255, 255));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (List<LiveImageryService.LngLat> ring : boundaries) {
            if (ring == null || ring.size() < 3) continue;
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (LiveImageryService.LngLat p : ring) {
                double[] px = lonLatToTilePixel(z, x, y, p.lng(), p.lat());
                if (first) {
                    path.moveTo(px[0], px[1]);
                    first = false;
                } else {
                    path.lineTo(px[0], px[1]);
                }
            }
            path.closePath();
            g.fill(path);
        }
        g.dispose();
        return mask;
    }

    private List<List<LiveImageryService.LngLat>> fallbackBoundariesFromBbox(LiveImageryService.LiveImageryResult imagery) {
        LiveImageryService.LngLat p1 = new LiveImageryService.LngLat(imagery.minLng(), imagery.minLat());
        LiveImageryService.LngLat p2 = new LiveImageryService.LngLat(imagery.maxLng(), imagery.minLat());
        LiveImageryService.LngLat p3 = new LiveImageryService.LngLat(imagery.maxLng(), imagery.maxLat());
        LiveImageryService.LngLat p4 = new LiveImageryService.LngLat(imagery.minLng(), imagery.maxLat());
        return List.of(List.of(p1, p2, p3, p4, p1));
    }

    private double[] lonLatToTilePixel(int z, int x, int y, double lon, double lat) {
        double latClamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double n = Math.pow(2.0, z);
        double xGlobal = (lon + 180.0) / 360.0 * n * tileSize;
        double latRad = Math.toRadians(latClamped);
        double yGlobal = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n * tileSize;
        return new double[]{xGlobal - x * tileSize, yGlobal - y * tileSize};
    }

    private static int lonToTileX(double lon, int z) {
        double n = Math.pow(2.0, z);
        return (int) Math.floor((lon + 180.0) / 360.0 * n);
    }

    private static int latToTileY(double lat, int z) {
        double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
        double n = Math.pow(2.0, z);
        return (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
    }

    private double pixelAreaAt(int z, int tileY, int py) {
        double nPixels = Math.pow(2.0, z) * tileSize;
        double globalY = tileY * (double) tileSize + py + 0.5;
        double merc = Math.PI * (1.0 - 2.0 * globalY / nPixels);
        double lat = Math.atan(Math.sinh(merc));
        double mpp = Math.cos(lat) * 2.0 * Math.PI * EARTH_RADIUS / nPixels;
        return mpp * mpp;
    }

    private static int gray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r + g + b) / 3;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int parseCssHexColor(String value, int fallbackRgb) {
        if (value == null) return fallbackRgb;
        String raw = value.trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        if (raw.length() == 3) {
            raw = "" + raw.charAt(0) + raw.charAt(0) + raw.charAt(1) + raw.charAt(1) + raw.charAt(2) + raw.charAt(2);
        }
        if (raw.length() != 6) return fallbackRgb;
        try {
            return Integer.parseInt(raw, 16) & 0x00FFFFFF;
        } catch (NumberFormatException e) {
            return fallbackRgb;
        }
    }

    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    public record WaterAreaStats(double waterAreaKm2, double analysisAreaKm2, double coverageRatio) {
    }

    public record WaterAreaChangeResult(
            String place,
            int startYear,
            int startMonth,
            int endYear,
            int endMonth,
            double ndwiMin,
            WaterAreaStats start,
            WaterAreaStats end,
            double deltaAreaKm2,
            double deltaPercent
    ) {
    }

    private record BandSource(String url, Integer bidx) {
    }
}
