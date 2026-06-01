package com.github.stormino.service;

import com.github.stormino.model.MonitoredShow;
import com.github.stormino.persistence.MonitoredShowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vixsrc.monitoring.enabled", havingValue = "true", matchIfMissing = true)
public class MonitoringScheduler {

    private final MonitoringService monitoringService;

    @Scheduled(fixedDelayString = "${vixsrc.monitoring.interval-ms:3600000}")
    public void checkAllMonitoredShows() {
        List<MonitoredShow> shows = monitoringService.listAll().stream()
                .filter(MonitoredShow::isEnabled)
                .toList();

        if (shows.isEmpty()) {
            log.debug("No enabled monitored shows, skipping check");
            return;
        }

        log.info("Starting monitoring check for {} show(s)", shows.size());
        int totalEnqueued = 0;
        for (MonitoredShow show : shows) {
            try {
                totalEnqueued += monitoringService.checkForNewEpisodes(show);
            } catch (Exception e) {
                log.error("Error checking show '{}': {}", show.getTitle(), e.getMessage(), e);
            }
        }
        log.info("Monitoring check complete: {} new episode(s) enqueued across {} show(s)",
                totalEnqueued, shows.size());
    }
}
