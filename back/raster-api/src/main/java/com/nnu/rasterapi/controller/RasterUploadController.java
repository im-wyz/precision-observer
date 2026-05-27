package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.RasterUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * COG：GET 须支持 Range（206）。下载路径使用 /file/** 并从 URI 解析文件名，避免 @PathVariable 误剥「.tif」；
 * 且 GDAL /vsicurl/ 需要 URL 上可见的 .tif/.tiff 等后缀，否则会报 unsupported file format。
 */

@RestController
@RequestMapping("/api/upload/raster")
@CrossOrigin(origins = "*")
public class RasterUploadController {

    private static final String FILE_PATH_MARKER = "/file/";

    private final RasterUploadService uploadService;

    public RasterUploadController(RasterUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RasterUploadResponse upload(@RequestPart("file") MultipartFile file) {
        RasterUploadService.RasterUploadResult r = uploadService.storeAndDescribe(file);
        return new RasterUploadResponse(
                r.displayName(),
                r.minLng(),
                r.minLat(),
                r.maxLng(),
                r.maxLat(),
                r.cogHttpUrl(),
                r.tileTemplateUrl(),
                r.tileMaxZoom(),
                r.tileMinZoom()
        );
    }

    @RequestMapping(value = "/file/**", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headFile(HttpServletRequest request) {
        String storedName = extractStoredFileName(request);
        Path path = uploadService.resolveStored(storedName);
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        long contentLength;
        try {
            contentLength = Files.size(path);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
        if (contentLength <= 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/tiff"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(contentLength)
                .build();
    }

    @RequestMapping(value = "/file/**", method = RequestMethod.GET)
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String storedName = extractStoredFileName(request);
        Path path = uploadService.resolveStored(storedName);
        if (!Files.isRegularFile(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long contentLength;
        try {
            contentLength = Files.size(path);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (contentLength <= 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, no-transform");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        String tiffType = MediaType.parseMediaType("image/tiff").toString();

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (!StringUtils.hasText(rangeHeader)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(tiffType);
            response.setContentLengthLong(contentLength);
            try (OutputStream out = response.getOutputStream()) {
                transferFileBytes(path, 0, contentLength, out);
            }
            return;
        }

        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(tiffType);
            response.setContentLengthLong(contentLength);
            try (OutputStream out = response.getOutputStream()) {
                transferFileBytes(path, 0, contentLength, out);
            }
            return;
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = end - start + 1;

        if (rangeLength <= 0 || start >= contentLength) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength);
            return;
        }

        // 直接写 Servlet 输出流：本环境 HttpMessageConverter 未处理 ResourceRegion / StreamingResponseBody，会 500；
        // GDAL /vsicurl/ 依赖 Range，读到错误 JSON 即报 unsupported file format。
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setContentType(tiffType);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength);
        response.setContentLengthLong(rangeLength);
        try (OutputStream out = response.getOutputStream()) {
            transferFileBytes(path, start, rangeLength, out);
        }
    }

    private static void transferFileBytes(Path path, long start, long length, OutputStream out) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             WritableByteChannel target = Channels.newChannel(out)) {
            long pos = start;
            long remaining = length;
            while (remaining > 0) {
                long n = channel.transferTo(pos, remaining, target);
                if (n <= 0) {
                    break;
                }
                pos += n;
                remaining -= n;
            }
        }
    }

    /**
     * 从 /api/upload/raster/file/&lt;storedName&gt; 解析 storedName（可含 .tif），不依赖 @PathVariable，避免后缀被框架吃掉。
     */
    private static String extractStoredFileName(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求 URI 无效");
        }
        int i = uri.indexOf(FILE_PATH_MARKER);
        if (i < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法下载路径");
        }
        String storedName = uri.substring(i + FILE_PATH_MARKER.length()).replaceAll("/+$", "");
        if (storedName.isBlank() || storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法文件名");
        }
        return storedName;
    }

    public record RasterUploadResponse(
            String displayName,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            String cogHttpUrl,
            String tileTemplateUrl,
            Integer tileMaxZoom,
            Integer tileMinZoom
    ) {}
}
