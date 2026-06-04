package com.nnu.rasterapi.controller;

import com.nnu.rasterapi.service.LocalImageryArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@RestController
@RequestMapping("/api/local-imagery")
@CrossOrigin(origins = "*")
public class LocalImageryController {
    private static final String MARKER = "/file/";
    private final LocalImageryArchiveService archiveService;

    public LocalImageryController(LocalImageryArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @RequestMapping(value = "/file/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void file(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String name = extractName(request);
        Path path = archiveService.resolve(name);
        if (!Files.isRegularFile(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long length = Files.size(path);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, no-transform");
        response.setContentType(MediaType.parseMediaType("image/tiff").toString());
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            response.setContentLengthLong(length);
            return;
        }
        String range = request.getHeader(HttpHeaders.RANGE);
        long start = 0;
        long end = length - 1;
        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring("bytes=".length()).split("-", 2);
            try {
                start = parts[0].isBlank() ? 0 : Long.parseLong(parts[0]);
                end = parts.length > 1 && !parts[1].isBlank() ? Long.parseLong(parts[1]) : end;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = length - 1;
            }
            start = Math.max(0, Math.min(start, length - 1));
            end = Math.max(start, Math.min(end, length - 1));
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        }
        long sendLength = end - start + 1;
        response.setContentLengthLong(sendLength);
        try (OutputStream out = response.getOutputStream();
             FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             WritableByteChannel target = Channels.newChannel(out)) {
            long pos = start;
            long remaining = sendLength;
            while (remaining > 0) {
                long n = channel.transferTo(pos, remaining, target);
                if (n <= 0) break;
                pos += n;
                remaining -= n;
            }
        }
    }

    private static String extractName(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int idx = uri.indexOf(MARKER);
        String name = idx >= 0 ? uri.substring(idx + MARKER.length()).replaceAll("/+$", "") : "";
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("非法文件名");
        }
        return name;
    }
}
