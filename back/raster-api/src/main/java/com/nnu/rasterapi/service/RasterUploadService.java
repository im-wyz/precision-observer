package com.nnu.rasterapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

@Service
public class RasterUploadService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_EXT = Pattern.compile("\\.(tif|tiff|geotiff|cog)$", Pattern.CASE_INSENSITIVE);
    /** OGC URI …/EPSG/0/32650 或字符串 EPSG:32650 */
    private static final Pattern EPSG_FROM_TEXT = Pattern.compile(
            "(?:/def/crs/EPSG/0/|/EPSG/0/)(\\d{4,5})|(?:EPSG|epsg)\\s*[:/]\\s*(\\d{4,5})");
    /** 目标 CRS：不调用 createFromName，避免依赖 jar 内 proj4/nad/epsg（部分环境读不到） */
    private static final String PROJ4_WGS84_LONG_LAT = "+proj=longlat +datum=WGS84 +no_defs";

    private final Path uploadDir;
    private final String publicBaseUrl;
    /** 拼进 TiTiler ?url= 的 COG 基址；默认同 publicBaseUrl，TiTiler 拉不到时可单独设为宿主机局域网 IP */
    private final String titilerCogFetchBase;
    private final String titilerBaseUrl;
    private final int tileSize;
    private final Duration timeout;
    private final HttpClient httpClient;

    public RasterUploadService(
            @Value("${upload.raster-dir:${java.io.tmpdir}/precision-observer-uploads}") String uploadDir,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${app.titiler-cog-base-url:}") String titilerCogBaseUrl,
            @Value("${live.titiler.base-url:http://localhost:8000}") String titilerBaseUrl,
            @Value("${live.titiler.tile-size:512}") int tileSize,
            @Value("${upload.titiler-timeout-ms:180000}") long titilerTimeoutMs
    ) {
        this.uploadDir = Path.of(uploadDir);
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        String cogBase = titilerCogBaseUrl == null || titilerCogBaseUrl.isBlank()
                ? this.publicBaseUrl
                : titilerCogBaseUrl.replaceAll("/$", "");
        this.titilerCogFetchBase = cogBase;
        this.titilerBaseUrl = titilerBaseUrl.replaceAll("/$", "");
        this.tileSize = tileSize;
        this.timeout = Duration.ofMillis(titilerTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @PostConstruct
    void ensureDir() throws Exception {
        Files.createDirectories(uploadDir);
    }

    public RasterUploadResult storeAndDescribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择影像文件");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效");
        }
        if (!SAFE_EXT.matcher(original).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .tif / .tiff / .geotiff / .cog");
        }
        // URL 须带 .tif/.tiff 等后缀：GDAL /vsicurl/ 对无扩展名地址常报「not recognized as being in a supported file format」
        String ext = original.substring(original.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        if (!ext.matches("\\.(tif|tiff|geotiff|cog)$")) {
            ext = ".tif";
        }
        String storedName = UUID.randomUUID() + ext;
        Path dest = uploadDir.resolve(storedName).toAbsolutePath().normalize();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(dest);
            } catch (Exception ignored) {}
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败: " + formatThrowable(e));
        }

        String cogHttpUrl = titilerCogFetchBase + "/api/upload/raster/file/" + storedName;
        TitilerCogDescribe desc = fetchCogDescribeFromTitiler(cogHttpUrl);
        double[] bbox = desc.wgs84Bbox();
        String tileQuery = UriComponentsBuilder.newInstance()
                .queryParam("url", cogHttpUrl)
                .queryParam("tilesize", tileSize)
                .encode(StandardCharsets.UTF_8)
                .build()
                .getQuery();
        // 本地上传预览：JPEG 无 alpha，COG 在 WebMercator 外包盒边角的无数据区常被涂成黑条；PNG 可走透明，观感更接近「只有数据」
        String uploadTileExt = "png";
        String tileTemplateUrl = titilerBaseUrl
                + "/cog/tiles/WebMercatorQuad/{z}/{x}/{y}." + uploadTileExt
                + "?" + tileQuery;

        return new RasterUploadResult(
                original, bbox[0], bbox[1], bbox[2], bbox[3], cogHttpUrl, tileTemplateUrl, desc.maxZoom(), desc.minZoom());
    }

    public Path resolveStored(String storedName) {
        if (storedName == null || storedName.isBlank() || storedName.contains("..") || storedName.contains("/") || storedName.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法文件名");
        }
        Path p = uploadDir.resolve(storedName).normalize();
        if (!p.startsWith(uploadDir.normalize())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法路径");
        }
        return p;
    }

    private record TitilerCogDescribe(double[] wgs84Bbox, Integer maxZoom, Integer minZoom) {}

    private TitilerCogDescribe fetchCogDescribeFromTitiler(String cogHttpUrl) {
        String infoUrl = UriComponentsBuilder.fromHttpUrl(titilerBaseUrl + "/cog/info")
                .queryParam("url", cogHttpUrl)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(infoUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String hint = "";
                if (body.contains("localhost") && (body.contains("Could not connect") || body.contains("Failed to connect"))) {
                    hint = " 【常见原因】TiTiler 在 Docker 内无法访问宿主机上的 localhost。"
                            + " 请在 application.properties 把 app.public-base-url 设为 http://host.docker.internal:8080（Linux Docker 启动 TiTiler 时增加 --add-host=host.docker.internal:host-gateway），或改用宿主机局域网 IP。";
                }
                if (body.contains("404") || body.toLowerCase(Locale.ROOT).contains("not found")) {
                    hint += " 【若含 404】TiTiler 已能连上 Spring，但 GET 影像文件失败。"
                            + " ① 在宿主机执行: curl -I \"" + cogHttpUrl + "\" 应返回 200。"
                            + " ② 在 TiTiler 容器内执行同一 curl；若此处 404/连不上，请把 app.titiler-cog-base-url（或 app.public-base-url）改为「容器内能访问的」宿主机地址，例如 http://192.168.x.x:8080。"
                            + " ③ Spring 与 TiTiler 都在本机非 Docker 时，两项一般都用 http://127.0.0.1:8080。";
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "TiTiler /cog/info 失败 HTTP " + resp.statusCode() + "。"
                                + " 请确认 TiTiler(" + titilerBaseUrl + ") 已启动，且能从该进程访问影像 URL："
                                + cogHttpUrl
                                + "。响应摘要: " + abbreviate(body, 400) + hint);
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(body);
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "TiTiler 返回非 JSON（无法解析范围）。URL=" + infoUrl + " 摘要: " + abbreviate(body, 400));
            }
            double[] nativeBbox = extractBounds(root);
            if (nativeBbox == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "TiTiler JSON 中未解析到 bounds/bbox。请确认文件为有效 GeoTIFF/COG。响应摘要: " + abbreviate(body, 500));
            }
            Integer epsg = extractEpsg(root);
            double[] wgs84 = normalizeBoundsToWgs84Degrees(nativeBbox, epsg);
            return new TitilerCogDescribe(wgs84, extractMaxZoom(root), extractMinZoom(root));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "请求 TiTiler 被中断。若 TiTiler 在 Docker 内，请检查 app.public-base-url 是否对其可达（例如 host.docker.internal:8080）。");
        } catch (IOException e) {
            if (isConnectionProblem(e)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "无法连接 TiTiler（当前配置为 " + titilerBaseUrl + "）。本地上传解析范围需要 TiTiler 已启动。"
                                + " 可在本机执行：docker run --rm -p 8000:8000 ghcr.io/developmentseed/titiler:latest"
                                + "；或在 application.properties 中将 live.titiler.base-url 改为你的 TiTiler 地址。");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "读取影像范围失败（IO）: " + formatThrowable(e) + "。TiTiler: " + titilerBaseUrl);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "读取影像范围失败: " + formatThrowable(e) + "。请求: " + abbreviate(infoUrl, 200));
        }
    }

    /** TiTiler /cog/info 中的 WebMercator 最大级别，供 Cesium 设置 maximumLevel */
    private static Integer extractMaxZoom(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        Integer z = readZoomInt(root.get("maxzoom"));
        if (z != null) {
            return z;
        }
        z = readZoomInt(root.get("max_zoom"));
        if (z != null) {
            return z;
        }
        JsonNode meta = root.path("metadata");
        if (meta.isObject()) {
            z = readZoomInt(meta.get("maxzoom"));
            if (z != null) {
                return z;
            }
            z = readZoomInt(meta.get("max_zoom"));
            if (z != null) {
                return z;
            }
        }
        JsonNode tilejson = root.path("tilejson");
        if (tilejson.isObject()) {
            z = readZoomInt(tilejson.get("maxzoom"));
            if (z != null) {
                return z;
            }
        }
        return null;
    }

    /** TiTiler /cog/info 中的 WebMercator 最小级别；区域景一般 >0，避免先铺满全球低清瓦片 */
    private static Integer extractMinZoom(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        Integer z = readZoomInt(root.get("minzoom"));
        if (z != null) {
            return z;
        }
        z = readZoomInt(root.get("min_zoom"));
        if (z != null) {
            return z;
        }
        JsonNode meta = root.path("metadata");
        if (meta.isObject()) {
            z = readZoomInt(meta.get("minzoom"));
            if (z != null) {
                return z;
            }
            z = readZoomInt(meta.get("min_zoom"));
            if (z != null) {
                return z;
            }
        }
        JsonNode tilejson = root.path("tilejson");
        if (tilejson.isObject()) {
            z = readZoomInt(tilejson.get("minzoom"));
            if (z != null) {
                return z;
            }
        }
        return null;
    }

    private static Integer readZoomInt(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        int v = n.intValue();
        if (v < 0 || v > 30) {
            return null;
        }
        return v;
    }

    private static boolean isConnectionProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return true;
            String m = t.getMessage();
            if (m != null && (m.contains("Connection refused") || m.contains("拒绝连接"))) return true;
        }
        return false;
    }

    private static String formatThrowable(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        String name = e.getClass().getSimpleName();
        Throwable c = e.getCause();
        if (c != null) {
            String cm = c.getMessage();
            if (cm != null && !cm.isBlank()) return name + ": " + cm;
            return name + " (cause=" + c.getClass().getSimpleName() + ")";
        }
        return name + " (无详细消息)";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    /** TiTiler /cog/info 各版本字段略有差异，尽量兼容避免解析异常导致 500 */
    private static double[] extractBounds(JsonNode root) {
        double[] a = tryParseBoundsFromNode(root.path("bounds"));
        if (a != null) return a;
        a = tryParseBoundsFromNode(root.path("bbox"));
        if (a != null) return a;
        JsonNode geo = root.path("geojson");
        if (geo.isObject()) {
            a = tryParseBoundsFromNode(geo.path("bbox"));
            if (a != null) return a;
        }
        JsonNode meta = root.path("metadata");
        if (meta.isObject()) {
            a = tryParseBoundsFromNode(meta.path("bounds"));
            if (a != null) return a;
        }
        // 部分部署：bounds 在 info / content 下
        JsonNode info = root.path("info");
        if (info.isObject()) {
            a = tryParseBoundsFromNode(info.path("bounds"));
            if (a != null) return a;
        }
        return null;
    }

    private static double[] tryParseBoundsFromNode(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isArray()) return tryParseBoundsArray(node);
        if (node.isObject()) return tryParseBoundsObject(node);
        return null;
    }

    /** [west, south, east, north] 或 [minx, miny, maxx, maxy] */
    private static double[] tryParseBoundsArray(JsonNode node) {
        if (!node.isArray() || node.size() < 4) return null;
        try {
            return new double[]{
                    node.get(0).asDouble(),
                    node.get(1).asDouble(),
                    node.get(2).asDouble(),
                    node.get(3).asDouble()
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] tryParseBoundsObject(JsonNode o) {
        if (!o.isObject()) return null;
        try {
            if (o.has("west") && o.has("south") && o.has("east") && o.has("north")) {
                return new double[]{
                        o.get("west").asDouble(),
                        o.get("south").asDouble(),
                        o.get("east").asDouble(),
                        o.get("north").asDouble()
                };
            }
            if (o.has("left") && o.has("bottom") && o.has("right") && o.has("top")) {
                return new double[]{
                        o.get("left").asDouble(),
                        o.get("bottom").asDouble(),
                        o.get("right").asDouble(),
                        o.get("top").asDouble()
                };
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * TiTiler /cog/info 的 bounds 多为数据集原生 CRS（如 UTM 米）；Cesium 需要 WGS84 经纬度。
     */
    private static double[] normalizeBoundsToWgs84Degrees(double[] nativeBbox, Integer epsg) {
        double minx = nativeBbox[0];
        double miny = nativeBbox[1];
        double maxx = nativeBbox[2];
        double maxy = nativeBbox[3];
        if (minx > maxx) {
            double t = minx;
            minx = maxx;
            maxx = t;
        }
        if (miny > maxy) {
            double t = miny;
            miny = maxy;
            maxy = t;
        }

        if (epsg != null && (epsg == 4326 || epsg == 4979)) {
            return clampWgs84(minx, miny, maxx, maxy);
        }
        if (epsg == null && looksLikeWgs84GeographicDegrees(minx, miny, maxx, maxy)) {
            return clampWgs84(minx, miny, maxx, maxy);
        }
        if (epsg == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "TiTiler 未返回可识别的 EPSG 代码，且 bounds 不像经纬度（度）。"
                            + " 若为投影坐标（如 UTM），请确认 GeoTIFF 内嵌 CRS，或升级 TiTiler。");
        }

        try {
            CRSFactory crsFactory = new CRSFactory();
            CoordinateReferenceSystem dst = crsFactory.createFromParameters("WGS84", PROJ4_WGS84_LONG_LAT);
            CoordinateReferenceSystem src = createSourceCrs(crsFactory, epsg);
            CoordinateTransform tx = new CoordinateTransformFactory().createTransform(src, dst);

            double minLon = Double.POSITIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY;
            double maxLon = Double.NEGATIVE_INFINITY;
            double maxLat = Double.NEGATIVE_INFINITY;
            double[][] corners = {{minx, miny}, {maxx, miny}, {maxx, maxy}, {minx, maxy}};
            ProjCoordinate srcP = new ProjCoordinate();
            ProjCoordinate dstP = new ProjCoordinate();
            for (double[] c : corners) {
                srcP.x = c[0];
                srcP.y = c[1];
                srcP.z = 0;
                tx.transform(srcP, dstP);
                double lon = dstP.x;
                double lat = dstP.y;
                minLon = Math.min(minLon, lon);
                minLat = Math.min(minLat, lat);
                maxLon = Math.max(maxLon, lon);
                maxLat = Math.max(maxLat, lat);
            }
            return clampWgs84(minLon, minLat, maxLon, maxLat);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "坐标转换到 WGS84 失败（EPSG:" + epsg + "）: " + formatThrowable(e));
        }
    }

    /**
     * 优先用内联 PROJ4（不读 nad/epsg）。覆盖 WGS84 UTM 南北半球与 Web Mercator；其它 EPSG 再尝试 createFromName。
     */
    private static CoordinateReferenceSystem createSourceCrs(CRSFactory crsFactory, int epsg) {
        String inline = proj4InlineForEpsg(epsg);
        if (inline != null) {
            return crsFactory.createFromParameters("EPSG:" + epsg, inline);
        }
        try {
            return crsFactory.createFromName("EPSG:" + epsg);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "未内置 EPSG:" + epsg + " 的离线 PROJ4 定义，且无法从 Proj4j 加载 CRS 库（"
                            + formatThrowable(e)
                            + "）。可将该坐标系 PROJ4 字符串补到 RasterUploadService.proj4InlineForEpsg。");
        }
    }

    /** @return PROJ4 字符串，或 null 表示走 createFromName */
    private static String proj4InlineForEpsg(int epsg) {
        if (epsg == 3857) {
            return "+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +k=1 +x_0=0 +y_0=0 +nadgrids=@null +wktext +units=m +no_defs";
        }
        if (epsg >= 32601 && epsg <= 32660) {
            return "+proj=utm +zone=" + (epsg - 32600) + " +datum=WGS84 +units=m +no_defs";
        }
        if (epsg >= 32701 && epsg <= 32760) {
            return "+proj=utm +zone=" + (epsg - 32700) + " +south +datum=WGS84 +units=m +no_defs";
        }
        return null;
    }

    private static double[] clampWgs84(double minLon, double minLat, double maxLon, double maxLat) {
        return new double[]{
                Math.max(-180, Math.min(180, minLon)),
                Math.max(-90, Math.min(90, minLat)),
                Math.max(-180, Math.min(180, maxLon)),
                Math.max(-90, Math.min(90, maxLat))
        };
    }

    private static boolean looksLikeWgs84GeographicDegrees(double minx, double miny, double maxx, double maxy) {
        if (!Double.isFinite(minx) || !Double.isFinite(miny) || !Double.isFinite(maxx) || !Double.isFinite(maxy)) {
            return false;
        }
        return minx >= -180 && maxx <= 180 && miny >= -90 && maxy <= 90
                && Math.abs(maxx - minx) <= 360 && Math.abs(maxy - miny) <= 180;
    }

    private static Integer extractEpsg(JsonNode root) {
        Integer n = tryEpsgFromNode(root.path("crs"));
        if (n != null) return n;
        n = tryEpsgFromNode(root.path("projection"));
        if (n != null) return n;
        n = tryEpsgFromNode(root.path("coordinate_system"));
        if (n != null) return n;
        JsonNode info = root.path("info");
        if (info.isObject()) {
            n = tryEpsgFromNode(info.path("crs"));
            if (n != null) return n;
        }
        JsonNode meta = root.path("metadata");
        if (meta.isObject()) {
            n = tryEpsgFromNode(meta.path("crs"));
            if (n != null) return n;
        }
        // 兜底：整段 JSON 文本里搜 EPSG（TiTiler 有时把 crs 放在深层）
        return firstEpsgInText(root.toString());
    }

    private static Integer tryEpsgFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isIntegralNumber()) {
            int v = node.intValue();
            return v >= 2000 && v <= 32767 ? v : null;
        }
        if (node.isTextual()) {
            return firstEpsgInText(node.asText());
        }
        if (node.isObject()) {
            if (node.has("epsg")) {
                JsonNode e = node.get("epsg");
                if (e.isIntegralNumber()) return e.intValue();
                if (e.isTextual()) return firstEpsgInText(e.asText());
            }
            Integer fromWkt = firstEpsgInText(node.toString());
            if (fromWkt != null) return fromWkt;
        }
        return null;
    }

    private static Integer firstEpsgInText(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = EPSG_FROM_TEXT.matcher(text);
        if (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String code = g1 != null ? g1 : g2;
            if (code != null) {
                try {
                    return Integer.parseInt(code);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public record RasterUploadResult(
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            String cogHttpUrl,
            String tileTemplateUrl,
            /** TiTiler 报告的 WebMercator maxzoom，可为 null */
            Integer tileMaxZoom,
            /** TiTiler 报告的 WebMercator minzoom，可为 null */
            Integer tileMinZoom
    ) {}
}
