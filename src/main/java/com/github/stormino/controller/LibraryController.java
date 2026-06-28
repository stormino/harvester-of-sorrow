package com.github.stormino.controller;

import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.service.MonitoringService;
import com.github.stormino.service.MonitoringService.LibraryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class LibraryController {

    private final MonitoringService monitoringService;

    @GetMapping
    public List<LibraryEntry> scanLibrary() {
        return monitoringService.scanLibrary();
    }

    @GetMapping("/monitored")
    public List<MonitoredShow> listMonitored() {
        return monitoringService.listAll();
    }

    @GetMapping("/monitored/{id}")
    public ResponseEntity<MonitoredShow> getMonitored(@PathVariable String id) {
        return monitoringService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/monitored")
    public MonitoredShow addMonitored(@RequestBody AddMonitoredRequest req) {
        return monitoringService.addMonitoredShow(
                req.title(), req.year(), req.tmdbId(),
                req.source(), req.sourceMetadata(), req.directoryName());
    }

    @PutMapping("/monitored/{id}")
    public ResponseEntity<Void> updateMonitored(@PathVariable String id,
                                                @RequestBody UpdateMonitoredRequest req) {
        return monitoringService.findById(id)
                .map(show -> {
                    monitoringService.updateSourceConfig(
                            id, req.title(), req.year(), req.tmdbId(),
                            req.source(), req.sourceMetadata());
                    return ResponseEntity.<Void>ok().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/monitored/{id}")
    public ResponseEntity<Void> removeMonitored(@PathVariable String id) {
        return monitoringService.findById(id)
                .map(show -> {
                    monitoringService.removeMonitoredShow(id);
                    return ResponseEntity.<Void>noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/monitored/{id}/enable")
    public ResponseEntity<Void> enableMonitoring(@PathVariable String id) {
        return monitoringService.findById(id)
                .map(show -> {
                    monitoringService.setEnabled(id, true);
                    return ResponseEntity.<Void>ok().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/monitored/{id}/disable")
    public ResponseEntity<Void> disableMonitoring(@PathVariable String id) {
        return monitoringService.findById(id)
                .map(show -> {
                    monitoringService.setEnabled(id, false);
                    return ResponseEntity.<Void>ok().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/monitored/{id}/check")
    public ResponseEntity<CheckResult> checkNow(@PathVariable String id) {
        return monitoringService.findById(id)
                .map(show -> {
                    int enqueued = monitoringService.checkForNewEpisodes(show);
                    return ResponseEntity.ok(new CheckResult(enqueued));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    public record AddMonitoredRequest(
            String title,
            Integer year,
            Integer tmdbId,
            MediaSource source,
            SourceMetadata sourceMetadata,
            String directoryName
    ) {}

    public record UpdateMonitoredRequest(
            String title,
            Integer year,
            Integer tmdbId,
            MediaSource source,
            SourceMetadata sourceMetadata
    ) {}

    public record CheckResult(int newEpisodesEnqueued) {}
}
