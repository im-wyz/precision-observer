package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
public class GreeningAnalysisService {
    private static final double EARTH_RADIUS = 6378137.0;

    private final LiveImageryService liveImageryService;
    private final HttpClient httpClient;
    private final String titilerBaseUrl;
    private final int tileSize;
    private final int analysisZoom;
    private final Duration timeout;

    public GreeningAnalysisService(
            LiveImageryService liveImageryService,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize,
            @Value("${analysis.greening.zoom:10}") int analysisZoom,
            @Value("${live.http-timeout-ms:12000}") long timeoutMs
    ) {
        this.liveImageryService = liveImageryService;
        this.titilerBaseUrl = titilerBaseUrl;
        this.tileSize = tileSize;
        this.analysisZoom = analysisZoom;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public GreeningChangeResult analyze(String place, YearMonth baseline, YearMonth target, double ndviThreshold) {
        LocalDate bStart = baseline.atDay(1);
        LocalDate bEnd = baseline.atEndOfMonth();
        LocalDate tStart = target.atDay(1);
        LocalDate tEnd = target.atEndOfMonth();

        LiveImageryService.LiveImageryResult baselineImagery = liveImageryService.queryByPlaceName(place, bStart, bEnd);
        LiveImageryService.LiveImageryResult targetImagery = liveImageryService.queryByPlaceName(place, tStart, tEnd);

        GreeningStats baseStats = computeGreeningStats(baselineImagery, ndviThreshold);
        GreeningStats targetStats = computeGreeningStats(targetImagery, ndviThreshold);

        return new GreeningChangeResult(
                place,
                baseline.getYear(), baseline.getMonthValue(),
                target.getYear(), target.getMonthValue(),
                ndviThreshold,
                baseStats,
                targetStats,
                targetStats.greenAreaM2 - baseStats.greenAreaM2,
                targetStats.greenRatio - baseStats.greenRatio
        );
    }

    private GreeningStats computeGreeningStats(LiveImageryService.LiveImageryResult imagery, double ndviThreshold) {
        List<List<LiveImageryService.LngLat>> boundaries = imagery.boundaries() == null ? List.of() : imagery.boundaries();
        if (boundaries.isEmpty()) return new GreeningStats(0, 0, 0, 0, imagery.coverageRatio());

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (List<LiveImageryService.LngLat> ring : boundaries) {
            for (LiveImageryService.LngLat p : ring) {
                int tx = lonToTileX(p.lng(), analysisZoom);
                int ty = latToTileY(p.lat(), analysisZoom);
                minX = Math.min(minX, tx);
                maxX = Math.max(maxX, tx);
                minY = Math.min(minY, ty);
                maxY = Math.max(maxY, ty);
            }
        }
        if (minX > maxX || minY > maxY) return new GreeningStats(0, 0, 0, 0, imagery.coverageRatio());

        double totalArea = 0.0;
        double greenArea = 0.0;
        long totalPixels = 0;
        long greenPixels = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                BufferedImage boundaryMask = buildMaskForTile(analysisZoom, x, y, boundaries);
                if (boundaryMask == null) continue;

                float[][] ndviGrid = new float[tileSize][tileSize];
                boolean[][] hasValue = new boolean[tileSize][tileSize];
                fillNdviGrid(analysisZoom, x, y, imagery.selectedScenes(), ndviGrid, hasValue);

                for (int py = 0; py < tileSize; py++) {
                    double pixelArea = pixelAreaAt(analysisZoom, y, py);
                    for (int px = 0; px < tileSize; px++) {
                        int a = (boundaryMask.getRGB(px, py) >>> 24) & 0xFF;
                        if (a == 0) continue;
                        if (!hasValue[py][px]) continue;
                        totalPixels++;
                        totalArea += pixelArea;
                        if (ndviGrid[py][px] >= ndviThreshold) {
                            greenPixels++;
                            greenArea += pixelArea;
                        }
                    }
                }
            }
        }

        double ratio = totalArea <= 0 ? 0 : greenArea / totalArea;
        return new GreeningStats(totalArea, greenArea, ratio, greenPixels, imagery.coverageRatio());
    }

    private void fillNdviGrid(
            int z,
            int x,
            int y,
            List<LiveImageryService.SelectedScene> scenes,
            float[][] ndviGrid,
            boolean[][] hasValue
    ) {
        if (scenes == null) return;
        for (LiveImageryService.SelectedScene scene : scenes) {
            if (scene.redCogUrl() == null || scene.nirCogUrl() == null) continue;
            BufferedImage red = fetchBandTile(z, x, y, scene.redCogUrl());
            BufferedImage nir = fetchBandTile(z, x, y, scene.nirCogUrl());
            if (red == null || nir == null) continue;

            boolean redHasAlpha = red.getColorModel().hasAlpha();
            boolean nirHasAlpha = nir.getColorModel().hasAlpha();

            int h = Math.min(tileSize, Math.min(red.getHeight(), nir.getHeight()));
            int w = Math.min(tileSize, Math.min(red.getWidth(), nir.getWidth()));
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    if (hasValue[py][px]) continue;
                    int ar = (red.getRGB(px, py) >>> 24) & 0xFF;
                    int an = (nir.getRGB(px, py) >>> 24) & 0xFF;
                    if ((redHasAlpha && ar == 0) || (nirHasAlpha && an == 0)) continue;
                    double redV = gray(red.getRGB(px, py));
                    double nirV = gray(nir.getRGB(px, py));
                    double den = nirV + redV;
                    if (den <= 1e-6) continue;
                    double ndvi = (nirV - redV) / den;
                    ndviGrid[py][px] = (float) ndvi;
                    hasValue[py][px] = true;
                }
            }
        }
    }

    private BufferedImage fetchBandTile(int z, int x, int y, String cogUrl) {
        try {
            String url = titilerBaseUrl.replaceAll("/$", "")
                    + "/cog/tiles/WebMercatorQuad/" + z + "/" + x + "/" + y + ".png"
                    + "?url=" + URLEncoder.encode(cogUrl, StandardCharsets.UTF_8)
                    + "&tilesize=" + tileSize
                    + "&rescale=0,10000";
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

    private BufferedImage buildMaskForTile(int z, int x, int y, List<List<LiveImageryService.LngLat>> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return null;
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
        double metersPerPixel = Math.cos(lat) * 2.0 * Math.PI * EARTH_RADIUS / nPixels;
        return metersPerPixel * metersPerPixel;
    }

    private static int gray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r + g + b) / 3;
    }

    public record GreeningStats(
            double totalAreaM2,
            double greenAreaM2,
            double greenRatio,
            long greenPixels,
            double coverageRatio
    ) {}

    public record GreeningChangeResult(
            String place,
            int baselineYear,
            int baselineMonth,
            int targetYear,
            int targetMonth,
            double ndviThreshold,
            GreeningStats baseline,
            GreeningStats target,
            double deltaGreenAreaM2,
            double deltaGreenRatio
    ) {}
}

