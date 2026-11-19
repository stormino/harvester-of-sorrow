package com.github.stormino.api.v1;

import com.github.stormino.api.dto.DiskDto;
import com.github.stormino.api.dto.SettingsDto;
import com.github.stormino.api.dto.SystemDto;
import com.github.stormino.api.dto.ToolCheckDto;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.MediaSource;
import com.github.stormino.service.source.MediaSourceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "System", description = "Version, configuration and tool checks")
public class SystemController {

    private final AppProperties properties;
    private final MediaSourceRegistry sourceRegistry;

    @Value("${app.version:unknown}")
    private String appVersion;

    @Operation(summary = "Runtime information and free disk space of the download paths")
    @GetMapping("/system")
    public SystemDto system() {
        var d = properties.getDownload();
        return new SystemDto(
                appVersion,
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                Runtime.getRuntime().availableProcessors(),
                List.of(disk("movies", d.getMoviesPath()),
                        disk("tvshows", d.getTvShowsPath()),
                        disk("temp", d.getTempPath())));
    }

    @Operation(summary = "Read-only configuration", description = "Change via environment variables and restart.")
    @GetMapping("/settings")
    public SettingsDto settings() {
        var d = properties.getDownload();
        String key = properties.getTmdb().getApiKey();
        boolean keySet = properties.getTmdb().isConfigured();
        return new SettingsDto(
                d.getMoviesPath(),
                d.getTvShowsPath(),
                d.getTempPath(),
                d.getParallelDownloads(),
                d.getSegmentConcurrency(),
                d.getDefaultQuality(),
                d.getDefaultLanguage(),
                properties.getExtractor().getBaseUrl(),
                properties.getExtractor().getTimeoutSeconds(),
                properties.getMonitoring().isEnabled(),
                describeCron(properties.getMonitoring().getCron()),
                properties.getMonitoring().getLanguageWaitDays(),
                keySet,
                keySet ? mask(key) : null,
                sourceRegistry.all().stream().anyMatch(p -> p.source() == MediaSource.RAIPLAY));
    }

    /** "0 0 3,6 * * *" → "daily at 03:00 and 06:00"; anything fancier is shown as the cron itself. */
    static String describeCron(String cron) {
        String[] f = cron.trim().split("\\s+");
        if (f.length == 6 && f[0].equals("0") && f[1].matches("\\d{1,2}") && f[2].matches("\\d{1,2}(,\\d{1,2})*")
                && f[3].equals("*") && f[4].equals("*") && f[5].equals("*")) {
            List<String> times = java.util.Arrays.stream(f[2].split(","))
                    .map(h -> String.format("%02d:%02d", Integer.parseInt(h), Integer.parseInt(f[1])))
                    .toList();
            String joined = times.size() == 1 ? times.get(0)
                    : String.join(", ", times.subList(0, times.size() - 1)) + " and " + times.get(times.size() - 1);
            return "daily at " + joined;
        }
        return "cron " + cron;
    }

    @Operation(summary = "Check that the external tools are installed",
            description = "ffmpeg (HLS downloads), 7zip and unrar (unpacking archives from links).")
    @PostMapping("/system/tools/check")
    public List<ToolCheckDto> checkTools() {
        return List.of(probe("ffmpeg", "ffmpeg", "-version"),
                probe("7zip", com.github.stormino.service.link.archive.ArchiveExtractor.sevenZipBinary()),
                probe("unrar", "unrar"));
    }

    static String mask(String key) {
        String k = key.trim();
        String tail = k.length() > 4 ? k.substring(k.length() - 4) : "";
        return "••••••••" + tail;
    }

    private static DiskDto disk(String label, String path) {
        Path p = Paths.get(path).toAbsolutePath();
        // Walk up to the nearest existing ancestor so a not-yet-created folder still reports its volume.
        File f = p.toFile();
        while (f != null && !f.exists()) {
            f = f.getParentFile();
        }
        if (f == null) {
            return new DiskDto(label, path, null, null);
        }
        return new DiskDto(label, path, f.getUsableSpace(), f.getTotalSpace());
    }

    /** Runs {@code command}; its first non-blank output line is reported as {@code tool}'s version. */
    private static ToolCheckDto probe(String tool, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
            String firstLine = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (firstLine == null && !line.isBlank()) {
                        firstLine = line.strip();
                    }
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ToolCheckDto(tool, false, null, "Timed out");
            }
            boolean ok = process.exitValue() == 0;
            return new ToolCheckDto(tool, ok, firstLine, ok ? null : "Exit code " + process.exitValue());
        } catch (Exception e) {
            log.warn("{} check failed: {}", tool, e.getMessage());
            return new ToolCheckDto(tool, false, null, e.getMessage());
        }
    }
}
