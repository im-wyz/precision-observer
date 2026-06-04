package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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
import java.util.List;

/**
 * 真实耕地面积变化分析服务（基于真实波段计算 NDVI 再做耕地像素统计）。
 *
 * 说明：
 * - 当前规则：把 NDVI 在 [min, max] 范围内的像素视为“耕地候选”。
 * - 该规则可后续替换为更复杂的分类模型，但接口不变。
 */
@Service
public class CroplandAnalysisService {
    private static final double EARTH_RADIUS = 6378137.0;

    private final LiveImageryService liveImageryService;
    private final HttpClient httpClient;
    private final String titilerBaseUrl;
    private final int tileSize;
    private final int zoom;
    private final Duration timeout;
    private final double ndviMin;
    private final double ndviMax;
    /** 配置为 min,max 时使用该 rescale；未配置则沿用 0,10000 */
    private final String tileRescaleParam;

    public CroplandAnalysisService(
            LiveImageryService liveImageryService,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize,
            @Value("${analysis.cropland.zoom:10}") int zoom,
            @Value("${analysis.cropland.ndvi-min:0.08}") double ndviMin,
            @Value("${analysis.cropland.ndvi-max:0.72}") double ndviMax,
            @Value("${analysis.cropland.tile-rescale:}") String tileRescale,
            @Value("${live.http-timeout-ms:12000}") long timeoutMs
    ) {
        this.liveImageryService = liveImageryService;
        this.titilerBaseUrl = titilerBaseUrl;
        this.tileSize = tileSize;
        this.zoom = zoom;
        this.ndviMin = ndviMin;
        this.ndviMax = ndviMax;
        String tr = tileRescale == null ? "" : tileRescale.trim();
        this.tileRescaleParam = tr.isEmpty() || !tr.contains(",") ? "" : tr;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public CroplandChangeResult analyze(String place, YearMonth startMonth, YearMonth endMonth) {
        if (startMonth == null || endMonth == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起止月份不能为空");
        }

        LiveImageryService.LiveImageryResult startImagery = liveImageryService.queryByPlaceNameForBandAnalysis(
                place, startMonth.atDay(1), startMonth.atEndOfMonth()
        );
        LiveImageryService.LiveImageryResult endImagery = liveImageryService.queryByPlaceNameForBandAnalysis(
                place, endMonth.atDay(1), endMonth.atEndOfMonth()
        );

        CroplandStats startStats = computeStats(startImagery);
        CroplandStats endStats = computeStats(endImagery);

        double deltaArea = round2(endStats.croplandAreaKm2 - startStats.croplandAreaKm2);
        double deltaPercent = startStats.croplandAreaKm2 <= 0 ? 0 : round2((deltaArea / startStats.croplandAreaKm2) * 100.0);

        return new CroplandChangeResult(
                place,
                startMonth.getYear(),
                startMonth.getMonthValue(),
                endMonth.getYear(),
                endMonth.getMonthValue(),
                startStats,
                endStats,
                deltaArea,
                deltaPercent
        );
    }

    private CroplandStats computeStats(LiveImageryService.LiveImageryResult imagery) {
        List<List<LiveImageryService.LngLat>> boundaries = imagery.boundaries();
        // 如果行政边界拿不到（例如 place 解析异常或区划接口偶发失败），用 bbox 做退化 mask
        if (boundaries == null || boundaries.isEmpty()) {
            boundaries = fallbackBoundariesFromBbox(imagery);
        }
        if (boundaries == null || boundaries.isEmpty()) {
            return new CroplandStats(0, 0, imagery.coverageRatio());
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
            return new CroplandStats(0, 0, imagery.coverageRatio());
        }

        double totalAreaM2 = 0.0;
        double croplandAreaM2 = 0.0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                BufferedImage adminMask = buildBoundaryMask(zoom, x, y, boundaries);
                if (adminMask == null) continue;

                float[][] ndviGrid = new float[tileSize][tileSize];
                boolean[][] hasValue = new boolean[tileSize][tileSize];
                fillNdviGrid(zoom, x, y, imagery.selectedScenes(), ndviGrid, hasValue);

                for (int py = 0; py < tileSize; py++) {
                    double pixelArea = pixelAreaAt(zoom, y, py);
                    for (int px = 0; px < tileSize; px++) {
                        int alpha = (adminMask.getRGB(px, py) >>> 24) & 0xFF;
                        if (alpha == 0 || !hasValue[py][px]) continue;
                        totalAreaM2 += pixelArea;
                        float ndvi = ndviGrid[py][px];
                        if (ndvi >= ndviMin && ndvi <= ndviMax) {
                            croplandAreaM2 += pixelArea;
                        }
                    }
                }
            }
        }

        return new CroplandStats(
                round2(croplandAreaM2 / 1_000_000.0),
                round2(totalAreaM2 / 1_000_000.0),
                imagery.coverageRatio()
        );
    }

    private List<List<LiveImageryService.LngLat>> fallbackBoundariesFromBbox(LiveImageryService.LiveImageryResult imagery) {
        // LiveImageryResult 的 admin bbox 已经来自行政区外包框，保证统计至少有有效区域
        LiveImageryService.LngLat p1 = new LiveImageryService.LngLat(imagery.minLng(), imagery.minLat());
        LiveImageryService.LngLat p2 = new LiveImageryService.LngLat(imagery.maxLng(), imagery.minLat());
        LiveImageryService.LngLat p3 = new LiveImageryService.LngLat(imagery.maxLng(), imagery.maxLat());
        LiveImageryService.LngLat p4 = new LiveImageryService.LngLat(imagery.minLng(), imagery.maxLat());

        List<LiveImageryService.LngLat> ring = List.of(p1, p2, p3, p4, p1);
        return List.of(ring);
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
                    // 无 alpha 通道时 getRGB 的高位可能恒为 0，误把整幅当透明；仅在有 alpha 时按透明像素跳过
                    if ((redHasAlpha && ar == 0) || (nirHasAlpha && an == 0)) continue;

                    double redVal = gray(red.getRGB(px, py));
                    double nirVal = gray(nir.getRGB(px, py));
                    double den = redVal + nirVal;
                    if (den <= 1e-6) continue;

                    ndviGrid[py][px] = (float) ((nirVal - redVal) / den);
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

    public record CroplandStats(
            double croplandAreaKm2,
            double adminAreaKm2,
            double coverageRatio
    ) {
    }

    public record CroplandChangeResult(
            String place,
            int startYear,
            int startMonth,
            int endYear,
            int endMonth,
            CroplandStats start,
            CroplandStats end,
            double deltaAreaKm2,
            double deltaPercent
    ) {
    }

    private record BandSource(String url, Integer bidx) {
    }
}
