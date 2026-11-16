package com.github.stormino.service;

import com.github.stormino.model.MonitoredShow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells whether a library show has finished airing, using TMDB's show status
 * ("Ended" / "Canceled" = completed; "Returning Series", "In Production",
 * "Planned", "Pilot" = ongoing).
 *
 * <p>Monitored shows use their own TMDB id. Plain folders are matched by name
 * ({@code Title.Year}, {@code Title (Year)}, ...) against TMDB search with the
 * year as a first-air-date filter. Both lookups are cached in memory so the
 * library endpoint only pays for TMDB on the first load (and after expiry).
 */
@Slf4j
@Service
public class ShowStatusService {

    static final Set<String> COMPLETED_STATUSES = Set.of("Ended", "Canceled");

    private static final int MAX_PARALLEL_LOOKUPS = 6;
    private static final Duration STATUS_TTL = Duration.ofHours(12);
    private static final Duration MATCH_TTL = Duration.ofDays(7);
    private static final Duration NO_MATCH_TTL = Duration.ofHours(12);

    /** Trailing year, optionally in parentheses/brackets, after a separator. */
    private static final Pattern TRAILING_YEAR =
            Pattern.compile("^(.+?)[\\s._-]*[(\\[]?((?:19|20)\\d{2})[)\\]]?$");

    private final TmdbMetadataService tmdb;
    private final Clock clock;

    private final Map<Integer, Cached<String>> statusByTmdbId = new ConcurrentHashMap<>();
    private final Map<String, Cached<Integer>> tmdbIdByFolder = new ConcurrentHashMap<>();

    @Autowired
    public ShowStatusService(TmdbMetadataService tmdb) {
        this(tmdb, Clock.systemUTC());
    }

    ShowStatusService(TmdbMetadataService tmdb, Clock clock) {
        this.tmdb = tmdb;
        this.clock = clock;
    }

    public record ShowStatus(@Nullable String status, @Nullable Integer tmdbId) {
        public static final ShowStatus UNKNOWN = new ShowStatus(null, null);

        public boolean completed() {
            return status != null && COMPLETED_STATUSES.contains(status);
        }
    }

    private record Cached<T>(@Nullable T value, Instant expiresAt) {}

    /**
     * Status for a library entry. A monitored show's own TMDB id wins; a
     * folder without one is matched by name. Unknown when TMDB is not
     * configured or nothing matches.
     */
    public ShowStatus statusFor(String directoryName, @Nullable MonitoredShow monitored) {
        if (!tmdb.isAvailable()) return ShowStatus.UNKNOWN;
        Integer id = monitored != null && monitored.getTmdbId() != null
                ? monitored.getTmdbId()
                : matchFolder(directoryName);
        if (id == null) return ShowStatus.UNKNOWN;
        return new ShowStatus(status(id), id);
    }

    /**
     * Statuses for many library entries at once (keyed by directory name).
     * Cold lookups run in parallel, at most {@value #MAX_PARALLEL_LOOKUPS} at a
     * time, so the first library load after startup stays quick.
     */
    public Map<String, ShowStatus> statusesFor(List<MonitoringService.LibraryEntry> entries) {
        Map<String, ShowStatus> out = new ConcurrentHashMap<>();
        if (!tmdb.isAvailable()) return out;
        Semaphore permits = new Semaphore(MAX_PARALLEL_LOOKUPS);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MonitoringService.LibraryEntry e : entries) {
                if (e.directoryName() == null) continue;
                pool.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        out.put(e.directoryName(), statusFor(e.directoryName(), e.monitoredShow()));
                    } catch (RuntimeException ex) {
                        log.warn("Status lookup failed for '{}': {}", e.directoryName(), ex.getMessage());
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return out;
    }

    /**
     * True only when the show's own TMDB id says it has ended. Used by the
     * scheduler, which must not skip a show on a guessed folder-name match.
     */
    public boolean isCompleted(MonitoredShow show) {
        if (!tmdb.isAvailable() || show.getTmdbId() == null) return false;
        String s = status(show.getTmdbId());
        return s != null && COMPLETED_STATUSES.contains(s);
    }

    @Nullable
    private String status(int tmdbId) {
        Cached<String> c = statusByTmdbId.get(tmdbId);
        if (fresh(c)) return c.value();
        Optional<String> s = tmdb.getTvShowStatus(tmdbId);
        // Errors are cached briefly as "unknown" too, so a TMDB outage does
        // not turn every library load into dozens of failing requests.
        statusByTmdbId.put(tmdbId, new Cached<>(s.orElse(null), now().plus(s.isPresent() ? STATUS_TTL : NO_MATCH_TTL)));
        return s.orElse(null);
    }

    @Nullable
    private Integer matchFolder(String directoryName) {
        Cached<Integer> c = tmdbIdByFolder.get(directoryName);
        if (fresh(c)) return c.value();
        FolderName parsed = parseFolderName(directoryName);
        Integer id = null;
        if (!parsed.title().isBlank()) {
            id = tmdb.findTvShowId(parsed.title(), parsed.year()).orElse(null);
            if (id == null && parsed.year() != null) {
                // Folder year can be off by one vs TMDB's first air date.
                id = tmdb.findTvShowId(parsed.title(), null).orElse(null);
            }
        }
        log.debug("Library folder '{}' -> TMDB match {}", directoryName, id);
        tmdbIdByFolder.put(directoryName, new Cached<>(id, now().plus(id != null ? MATCH_TTL : NO_MATCH_TTL)));
        return id;
    }

    record FolderName(String title, @Nullable Integer year) {}

    /** {@code "For.All.Mankind.2019"} → ("For All Mankind", 2019). */
    static FolderName parseFolderName(String directoryName) {
        String name = directoryName.trim();
        Integer year = null;
        Matcher m = TRAILING_YEAR.matcher(name);
        if (m.matches()) {
            name = m.group(1);
            year = Integer.parseInt(m.group(2));
        }
        String title = name.replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
        return new FolderName(title, year);
    }

    private boolean fresh(@Nullable Cached<?> c) {
        return c != null && now().isBefore(c.expiresAt());
    }

    private Instant now() {
        return clock.instant();
    }
}
