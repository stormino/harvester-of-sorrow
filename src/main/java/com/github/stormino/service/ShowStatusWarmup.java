package com.github.stormino.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills the {@link ShowStatusService} cache in the background at startup, so
 * the first Library load does not wait for dozens of TMDB lookups.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowStatusWarmup {

    private final MonitoringService monitoringService;
    private final ShowStatusService showStatusService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread.ofVirtual().name("show-status-warmup").start(() -> {
            try {
                var entries = monitoringService.scanLibrary();
                long start = System.currentTimeMillis();
                var statuses = showStatusService.statusesFor(entries);
                log.info("Show status cache warmed: {} of {} library folder(s) resolved in {} ms",
                        statuses.values().stream().filter(s -> s.status() != null).count(),
                        entries.size(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("Show status warm-up failed: {}", e.getMessage());
            }
        });
    }
}
