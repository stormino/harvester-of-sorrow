package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.SourceRefCodec;
import com.github.stormino.api.TaskMapper;
import com.github.stormino.api.dto.CheckResultDto;
import com.github.stormino.api.dto.LibraryEntryDto;
import com.github.stormino.api.dto.MonitoredShowDto;
import com.github.stormino.api.dto.MonitoredShowRequest;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.service.MonitoringService;
import com.github.stormino.service.ShowStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/library")
@RequiredArgsConstructor
@Tag(name = "Library", description = "TV library folders and monitoring")
public class LibraryController {

    private final MonitoringService monitoring;
    private final ShowStatusService showStatus;
    private final SourceRefCodec sourceRefCodec;
    private final AppProperties properties;

    @Operation(summary = "Library entries",
            description = "Every folder under DOWNLOAD_TV_SHOWS_PATH plus monitored shows whose folder is gone, "
                    + "with on-disk season/episode counts, monitoring config and TMDB airing status "
                    + "(folders without a TMDB id are matched by name).")
    @GetMapping
    public List<LibraryEntryDto> library() {
        Path base = Paths.get(properties.getDownload().getTvShowsPath());
        List<MonitoringService.LibraryEntry> entries = monitoring.scanLibrary();
        Map<String, ShowStatusService.ShowStatus> statuses = showStatus.statusesFor(entries);
        return entries.stream()
                .map(e -> {
                    ShowStatusService.ShowStatus st = statuses.getOrDefault(
                            e.directoryName(), ShowStatusService.ShowStatus.UNKNOWN);
                    return new LibraryEntryDto(
                            e.directoryName(),
                            e.seasonCount(),
                            e.episodeCount(),
                            e.directoryName() != null && Files.isDirectory(base.resolve(e.directoryName())),
                            st.completed(),
                            st.status(),
                            e.monitoredShow() != null ? toDto(e.monitoredShow()) : null);
                })
                .sorted(Comparator.comparing(LibraryEntryDto::directoryName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Operation(summary = "List monitored shows")
    @GetMapping("/monitored")
    public List<MonitoredShowDto> listMonitored() {
        return monitoring.listAll().stream().map(this::toDto).toList();
    }

    @Operation(summary = "Get a monitored show")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/monitored/{id}")
    public MonitoredShowDto getMonitored(@PathVariable String id) {
        return toDto(find(id));
    }

    @Operation(summary = "Start monitoring a library folder")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "Folder already monitored")
    @PostMapping("/monitored")
    @ResponseStatus(HttpStatus.CREATED)
    public MonitoredShowDto addMonitored(@Valid @RequestBody MonitoredShowRequest req) {
        if (req.directoryName() == null || req.directoryName().isBlank()) {
            throw ApiException.badRequest("directoryName is required");
        }
        boolean exists = monitoring.listAll().stream()
                .anyMatch(s -> s.getDirectoryName().equals(req.directoryName()));
        if (exists) {
            throw ApiException.conflict("Folder '" + req.directoryName() + "' is already monitored");
        }
        SourceMetadata meta = sourceRefCodec.decode(req.sourceRef(), req.source());
        return toDto(monitoring.addMonitoredShow(req.title(), req.year(), tmdbId(req, meta),
                req.source(), meta, req.directoryName()));
    }

    @Operation(summary = "Change a monitored show's source match")
    @ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/monitored/{id}")
    public MonitoredShowDto updateMonitored(@PathVariable String id, @Valid @RequestBody MonitoredShowRequest req) {
        find(id);
        SourceMetadata meta = sourceRefCodec.decode(req.sourceRef(), req.source());
        monitoring.updateSourceConfig(id, req.title(), req.year(), tmdbId(req, meta), req.source(), meta);
        return toDto(find(id));
    }

    @Operation(summary = "Stop monitoring a show", description = "Files on disk are kept.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "Not found")
    @DeleteMapping("/monitored/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMonitored(@PathVariable String id) {
        find(id);
        monitoring.removeMonitoredShow(id);
    }

    @Operation(summary = "Resume monitoring")
    @PostMapping("/monitored/{id}/enable")
    public MonitoredShowDto enable(@PathVariable String id) {
        find(id);
        monitoring.setEnabled(id, true);
        return toDto(find(id));
    }

    @Operation(summary = "Pause monitoring")
    @PostMapping("/monitored/{id}/disable")
    public MonitoredShowDto disable(@PathVariable String id) {
        find(id);
        monitoring.setEnabled(id, false);
        return toDto(find(id));
    }

    @Operation(summary = "Check for new episodes now",
            description = "Queues episodes newer than the latest one on disk. Returns how many were queued.")
    @PostMapping("/monitored/{id}/check")
    public CheckResultDto check(@PathVariable String id) {
        return new CheckResultDto(monitoring.checkForNewEpisodes(find(id)));
    }

    private MonitoredShow find(String id) {
        return monitoring.findById(id).orElseThrow(() -> ApiException.notFound("Monitored show", id));
    }

    private static Integer tmdbId(MonitoredShowRequest req, SourceMetadata meta) {
        if (meta instanceof VixSrcMetadata v) {
            return v.tmdbId();
        }
        return req.tmdbId();
    }

    private MonitoredShowDto toDto(MonitoredShow s) {
        return new MonitoredShowDto(
                s.getId(),
                s.getTitle(),
                s.getYear(),
                s.getTmdbId(),
                s.getSource(),
                sourceRefCodec.encode(s.getSourceMetadata()),
                s.getDirectoryName(),
                s.isEnabled(),
                TaskMapper.toInstant(s.getLastCheckedAt()),
                TaskMapper.toInstant(s.getLastNewEpisodeAt()),
                TaskMapper.toInstant(s.getCreatedAt()));
    }
}
