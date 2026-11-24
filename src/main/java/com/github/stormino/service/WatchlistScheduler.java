package com.github.stormino.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once a day, looks for the watchlist titles no source had when they were saved. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hos.watchlist.enabled", havingValue = "true", matchIfMissing = true)
public class WatchlistScheduler {

    private final WatchlistAvailabilityService availability;

    @Scheduled(cron = "${hos.watchlist.cron:0 0 7 * * *}")
    public void checkWaitingTitles() {
        try {
            availability.checkAll();
        } catch (Exception e) {
            log.error("Watchlist check failed: {}", e.getMessage(), e);
        }
    }
}
