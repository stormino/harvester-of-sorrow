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
import java.util.Optional;

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
        Optional<MonitoredShow> show = monitoringService.findById(id);
        return show.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        if (monitoringService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        monitoringService.updateSourceConfig(id, req.title(), req.year(), req.tmdbId(),
                req.source(), req.sourceMetadata());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/monitored/{id}")
    public ResponseEntity<Void> removeMonitored(@PathVariable String id) {
        if (monitoringService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        monitoringService.removeMonitoredShow(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/monitored/{id}/enable")
    public ResponseEntity<Void> enableMonitoring(@PathVariable String id) {
        if (monitoringService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        monitoringService.setEnabled(id, true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/monitored/{id}/disable")
    public ResponseEntity<Void> disableMonitoring(@PathVariable String id) {
        if (monitoringService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        monitoringService.setEnabled(id, false);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/monitored/{id}/check")
    public ResponseEntity<CheckResult> checkNow(@PathVariable String id) {
        Optional<MonitoredShow> show = monitoringService.findById(id);
        if (show.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int enqueued = monitoringService.checkForNewEpisodes(show.get());
        return ResponseEntity.ok(new CheckResult(enqueued));
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
