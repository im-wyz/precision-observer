package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class LiveTileService {
    private final HttpClient httpClient;
    private final String titilerBaseUrl;
    private final int tileSize;
    private final Duration timeout;

    public LiveTileService(
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize,
            @Value("${live.http-timeout-ms:12000}") long timeoutMs
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        this.titilerBaseUrl = titilerBaseUrl;
        this.tileSize = tileSize;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public byte[] renderMaskedTile(
            int z,
            int x,
            int y,
            List<String> cogUrls,
            List<List<List<LiveImageryService.LngLat>>> footprints,
            List<List<LiveImageryService.LngLat>> boundaries
    ) {
        if (cogUrls == null || cogUrls.isEmpty()) {
            return transparentTile(tileSize, tileSize);
        }

        BufferedImage composed = null;
        for (int i = 0; i < cogUrls.size(); i++) {
            String cogUrl = cogUrls.get(i);
            BufferedImage img = fetchTitilerTile(z, x, y, cogUrl);
            if (img == null) continue;
            // 关键：按每一景自己的 footprint 几何裁剪，保证“几何拼接”能发生
            if (footprints != null && i < footprints.size()) {
                List<List<LiveImageryService.LngLat>> fpRings = footprints.get(i);
                if (fpRings != null && !fpRings.isEmpty()) {
                    img = applyPolygonMask(z, x, y, img, fpRings);
                }
            }
            if (composed == null) {
                composed = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            }
            mergeFillTransparent(composed, img);
        }

        if (composed == null) {
            // 对没有命中影像的瓦片返回透明图，避免前端因 4xx/5xx 连续报错导致整层不可见
            return transparentTile(tileSize, tileSize);
        }

        BufferedImage masked = applyPolygonMask(z, x, y, composed, boundaries == null ? List.of() : boundaries);

        return toPng(masked);
    }

    private BufferedImage fetchTitilerTile(int z, int x, int y, String cogUrl) {
        try {
            String url = titilerBaseUrl.replaceAll("/$", "") +
                    "/cog/tiles/WebMercatorQuad/" + z + "/" + x + "/" + y + ".png" +
                    "?url=" + URLEncoder.encode(cogUrl, StandardCharsets.UTF_8) +
                    "&tilesize=" + tileSize;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "image/png")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    private BufferedImage applyPolygonMask(
            int z,
            int x,
            int y,
            BufferedImage tile,
            List<List<LiveImageryService.LngLat>> boundaries
    ) {
        if (boundaries == null || boundaries.isEmpty()) {
            return tile;
        }

        BufferedImage mask = new BufferedImage(tile.getWidth(), tile.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 255));

        for (List<LiveImageryService.LngLat> ring : boundaries) {
            if (ring == null || ring.size() < 3) continue;
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (LiveImageryService.LngLat p : ring) {
                Pixel px = lonLatToTilePixel(z, x, y, p.lng(), p.lat(), tileSize);
                if (first) {
                    path.moveTo(px.x, px.y);
                    first = false;
                } else {
                    path.lineTo(px.x, px.y);
                }
            }
            path.closePath();
            g.fill(path);
        }
        g.dispose();

        BufferedImage out = new BufferedImage(tile.getWidth(), tile.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D og = out.createGraphics();
        og.drawImage(tile, 0, 0, null);
        og.setComposite(AlphaComposite.DstIn);
        og.drawImage(mask, 0, 0, null);
        og.dispose();
        return out;
    }

    private static Pixel lonLatToTilePixel(int z, int x, int y, double lon, double lat, int tileSize) {
        // WebMercator (EPSG:3857) tile coordinate -> pixel
        double latClamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double n = Math.pow(2.0, z);
        double xGlobal = (lon + 180.0) / 360.0 * n * tileSize;
        double latRad = Math.toRadians(latClamped);
        double yGlobal = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n * tileSize;

        double x0 = x * tileSize;
        double y0 = y * tileSize;
        return new Pixel(xGlobal - x0, yGlobal - y0);
    }

    private static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PNG 编码失败", e);
        }
    }

    private static void mergeFillTransparent(BufferedImage base, BufferedImage overlay) {
        int width = Math.min(base.getWidth(), overlay.getWidth());
        int height = Math.min(base.getHeight(), overlay.getHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int baseArgb = base.getRGB(x, y);
                int baseAlpha = (baseArgb >>> 24) & 0xFF;
                if (baseAlpha != 0) continue; // 仅填补透明洞，不覆盖已有像素
                int overArgb = overlay.getRGB(x, y);
                int overAlpha = (overArgb >>> 24) & 0xFF;
                if (overAlpha == 0) continue;
                base.setRGB(x, y, overArgb);
            }
        }
    }

    private static byte[] transparentTile(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        return toPng(image);
    }

    private record Pixel(double x, double y) {
    }
}

