package com.github.stormino.controller;

import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.service.MonitoringService;
import com.github.stormino.service.MonitoringService.LibraryEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
@Tag(name = "Library")
public class LibraryController {

    private final MonitoringService monitoringService;

    @Operation(
        summary = "Scan the TV library",
        description = "Scans `DOWNLOAD_TV_SHOWS_PATH` on disk and returns all show directories, " +
                      "annotated with season/episode counts and their monitoring status."
    )
    @ApiResponse(responseCode = "200", description = "Library entries",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = LibraryEntry.class))))
    @GetMapping
    public List<LibraryEntry> scanLibrary() {
        log.info("Scanning TV library");
        List<LibraryEntry> entries = monitoringService.scanLibrary();
        log.info("Library scan returned {} entries", entries.size());
        return entries;
    }

    @Operation(summary = "List all monitored shows")
    @ApiResponse(responseCode = "200", description = "Monitored shows",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = MonitoredShow.class))))
    @GetMapping("/monitored")
    public List<MonitoredShow> listMonitored() {
        log.info("Listing monitored shows");
        List<MonitoredShow> shows = monitoringService.listAll();
        log.info("Returning {} monitored shows", shows.size());
        return shows;
    }

    @Operation(summary = "Get a monitored show by ID")
    @ApiResponse(responseCode = "200", description = "Monitored show",
        content = @Content(schema = @Schema(implementation = MonitoredShow.class)))
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @GetMapping("/monitored/{id}")
    public ResponseEntity<MonitoredShow> getMonitored(
            @Parameter(description = "Monitored show UUID") @PathVariable String id) {
        log.info("Getting monitored show: {}", id);
        Optional<MonitoredShow> show = monitoringService.findById(id);
        if (show.isEmpty()) {
            log.warn("Monitored show not found: {}", id);
        }
        return show.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
        summary = "Add a monitored show",
        description = "Link a show directory to a source so new episodes are auto-queued. " +
                      "`directoryName` must match an existing folder under `DOWNLOAD_TV_SHOWS_PATH`."
    )
    @ApiResponse(responseCode = "200", description = "Monitored show created",
        content = @Content(schema = @Schema(implementation = MonitoredShow.class)))
    @PostMapping("/monitored")
    public MonitoredShow addMonitored(@RequestBody AddMonitoredRequest req) {
        log.info("Adding monitored show: title='{}', source={}", req.title(), req.source());
        MonitoredShow show = monitoringService.addMonitoredShow(
                req.title(), req.year(), req.tmdbId(),
                req.source(), req.sourceMetadata(), req.directoryName());
        log.info("Monitored show added: {}", show.getId());
        return show;
    }

    @Operation(summary = "Update a monitored show's source configuration")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @PutMapping("/monitored/{id}")
    public ResponseEntity<Void> updateMonitored(
            @Parameter(description = "Monitored show UUID") @PathVariable String id,
            @RequestBody UpdateMonitoredRequest req) {
        log.info("Updating monitored show: {}", id);
        if (monitoringService.findById(id).isEmpty()) {
            log.warn("Monitored show not found for update: {}", id);
            return ResponseEntity.notFound().build();
        }
        monitoringService.updateSourceConfig(id, req.title(), req.year(), req.tmdbId(),
                req.source(), req.sourceMetadata());
        log.info("Monitored show updated: {}", id);
        return ResponseEntity.ok().build();
    }

    @Operation(
        summary = "Remove a monitored show",
        description = "Stops monitoring. Already-downloaded files are kept on disk."
    )
    @ApiResponse(responseCode = "204", description = "Removed", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @DeleteMapping("/monitored/{id}")
    public ResponseEntity<Void> removeMonitored(
            @Parameter(description = "Monitored show UUID") @PathVariable String id) {
        log.info("Removing monitored show: {}", id);
        if (monitoringService.findById(id).isEmpty()) {
            log.warn("Monitored show not found for removal: {}", id);
            return ResponseEntity.notFound().build();
        }
        monitoringService.removeMonitoredShow(id);
        log.info("Monitored show removed: {}", id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Enable (resume) monitoring for a show")
    @ApiResponse(responseCode = "200", description = "Enabled", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @PostMapping("/monitored/{id}/enable")
    public ResponseEntity<Void> enableMonitoring(
            @Parameter(description = "Monitored show UUID") @PathVariable String id) {
        log.info("Enabling monitoring for show: {}", id);
        if (monitoringService.findById(id).isEmpty()) {
            log.warn("Monitored show not found for enable: {}", id);
            return ResponseEntity.notFound().build();
        }
        monitoringService.setEnabled(id, true);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Disable (pause) monitoring for a show")
    @ApiResponse(responseCode = "200", description = "Disabled", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @PostMapping("/monitored/{id}/disable")
    public ResponseEntity<Void> disableMonitoring(
            @Parameter(description = "Monitored show UUID") @PathVariable String id) {
        log.info("Disabling monitoring for show: {}", id);
        if (monitoringService.findById(id).isEmpty()) {
            log.warn("Monitored show not found for disable: {}", id);
            return ResponseEntity.notFound().build();
        }
        monitoringService.setEnabled(id, false);
        return ResponseEntity.ok().build();
    }

    @Operation(
        summary = "Trigger an immediate episode check",
        description = "Checks the source for new episodes right now and enqueues any that are missing from disk. " +
                      "Returns the number of episodes enqueued."
    )
    @ApiResponse(responseCode = "200", description = "Check complete",
        content = @Content(schema = @Schema(implementation = CheckResult.class)))
    @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    @PostMapping("/monitored/{id}/check")
    public ResponseEntity<CheckResult> checkNow(
            @Parameter(description = "Monitored show UUID") @PathVariable String id) {
        log.info("Triggering episode check for monitored show: {}", id);
        Optional<MonitoredShow> show = monitoringService.findById(id);
        if (show.isEmpty()) {
            log.warn("Monitored show not found for check: {}", id);
            return ResponseEntity.notFound().build();
        }
        int enqueued = monitoringService.checkForNewEpisodes(show.get());
        log.info("Episode check complete for show {}: {} episodes enqueued", id, enqueued);
        return ResponseEntity.ok(new CheckResult(enqueued));
    }

    @Schema(description = "Request body for adding a monitored show")
    public record AddMonitoredRequest(
            @Schema(description = "Show title", example = "Breaking Bad") String title,
            @Schema(description = "Release year", example = "2008") Integer year,
            @Schema(description = "TMDB ID", example = "1396") Integer tmdbId,
            @Schema(description = "Source to monitor", example = "VIXSRC") MediaSource source,
            @Schema(description = "Source-specific metadata") SourceMetadata sourceMetadata,
            @Schema(description = "Directory name under DOWNLOAD_TV_SHOWS_PATH", example = "Breaking.Bad.2008") String directoryName
    ) {}

    @Schema(description = "Request body for updating a monitored show's source config")
    public record UpdateMonitoredRequest(
            @Schema(description = "Show title", example = "Breaking Bad") String title,
            @Schema(description = "Release year", example = "2008") Integer year,
            @Schema(description = "TMDB ID", example = "1396") Integer tmdbId,
            @Schema(description = "Source", example = "RAIPLAY") MediaSource source,
            @Schema(description = "Source-specific metadata") SourceMetadata sourceMetadata
    ) {}

    @Schema(description = "Result of an immediate episode check")
    public record CheckResult(
            @Schema(description = "Number of new episodes enqueued", example = "3") int newEpisodesEnqueued
    ) {}
}
