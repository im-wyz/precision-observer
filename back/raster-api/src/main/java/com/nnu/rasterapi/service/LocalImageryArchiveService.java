package com.nnu.rasterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalImageryArchiveService {
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[_-](\\d{1,2})[_-](\\d{1,2})");

    private final Path imageryDir;
    private final String publicBaseUrl;

    public LocalImageryArchiveService(
            @Value("${live.local-imagery.dir:E:/yaogandata}") String imageryDir,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.imageryDir = Path.of(imageryDir);
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    public Optional<LocalArchiveMatch> findBestBandMatch(String place, LocalDate startDate, LocalDate endDate) {
        return findBest(place, startDate, endDate, true);
    }

    public Optional<LocalArchiveMatch> findBestMatch(String place, LocalDate startDate, LocalDate endDate) {
        return findBest(place, startDate, endDate, false);
    }

    public String buildCogHttpUrl(String fileName) {
        return publicBaseUrl + "/api/local-imagery/file/" + fileName + "?v=" + System.currentTimeMillis();
    }

    public Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("非法文件名");
        }
        return imageryDir.resolve(fileName).normalize();
    }

    private Optional<LocalArchiveMatch> findBest(String place, LocalDate startDate, LocalDate endDate, boolean bandRequired) {
        if (!Files.isDirectory(imageryDir)) return Optional.empty();
        String placeKey = normalizePlace(place);
        try (var stream = Files.list(imageryDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isTif(p.getFileName().toString()))
                    .map(p -> toMatch(p.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .filter(m -> placeKey.isBlank() || m.fileName().toLowerCase(Locale.ROOT).contains(placeKey))
                    .filter(m -> !bandRequired || hasRequiredBands(m.fileName()))
                    .min(Comparator.comparingInt(m -> score(m, startDate, endDate)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean hasRequiredBands(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.contains("multiband") || n.contains("b04_b08") || n.contains("b4_b8");
    }

    private static int score(LocalArchiveMatch m, LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) return 0;
        LocalDate target = startDate != null && endDate != null ? startDate.plusDays(Math.max(0, (endDate.toEpochDay() - startDate.toEpochDay()) / 2)) : (startDate != null ? startDate : endDate);
        LocalDate scene = LocalDate.of(m.year(), m.month(), Math.min(28, Math.max(1, m.day())));
        return (int) Math.abs(scene.toEpochDay() - target.toEpochDay());
    }

    private static Optional<LocalArchiveMatch> toMatch(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(new LocalArchiveMatch(fileName, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
    }

    private static boolean isTif(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static String normalizePlace(String place) {
        String p = place == null ? "" : place.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "南京", "南京市" -> "nanjing";
            default -> p.replace("市", "");
        };
    }

    public record LocalArchiveMatch(String fileName, int year, int month, int day) {}
}
