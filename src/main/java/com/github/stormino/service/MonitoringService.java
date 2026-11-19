package com.github.stormino.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.MediaInfo;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.persistence.MonitoredShowRecord;
import com.github.stormino.persistence.MonitoredShowRepository;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.util.PathUtils;
import com.github.stormino.util.ShowDirectoryScanner;
import com.github.stormino.util.ShowDirectoryScanner.EpisodeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final MonitoredShowRepository repository;
    private final DownloadQueueService downloadQueueService;
    private final MediaSourceRegistry sourceRegistry;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    public List<MonitoredShow> listAll() {
        return StreamSupport.stream(repository.findAll().spliterator(), false)
                .map(this::fromRecord)
                .toList();
    }

    public Optional<MonitoredShow> findById(String id) {
        return repository.findById(id).map(this::fromRecord);
    }

    public MonitoredShow addMonitoredShow(String title, Integer year, Integer tmdbId,
                                          MediaSource source, SourceMetadata sourceMetadata,
                                          String directoryName) {
        MonitoredShow show = MonitoredShow.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .year(year)
                .tmdbId(tmdbId)
                .source(source != null ? source : MediaSource.VIXSRC)
                .sourceMetadata(sourceMetadata)
                .directoryName(directoryName)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();

        MonitoredShowRecord r = toRecord(show);
        repository.insert(r.getId(), r.getTitle(), r.getYear(), r.getTmdbId(),
                r.getSource(), r.getSourceMetadata(), r.getDirectoryName(),
                r.getEnabled(), r.getLastCheckedAt(), r.getLastNewEpisodeAt(), r.getCreatedAt());

        log.info("Added monitored show: {} (source={})", title, show.getSource());
        return show;
    }

    public void removeMonitoredShow(String id) {
        repository.deleteById(id);
        log.info("Removed monitored show: {}", id);
    }

    public void setEnabled(String id, boolean enabled) {
        repository.updateEnabled(id, enabled ? 1 : 0);
        log.info("Monitoring {} for show {}", enabled ? "enabled" : "disabled", id);
    }

    public void updateSourceConfig(String id, String title, Integer year, Integer tmdbId,
                                   MediaSource source, SourceMetadata sourceMetadata) {
        repository.updateSourceConfig(id, title, year, tmdbId,
                source != null ? source.name() : MediaSource.VIXSRC.name(),
                serializeSourceMetadata(sourceMetadata));
        log.info("Updated source config for monitored show {}: source={}", id, source);
    }

    // -------------------------------------------------------------------------
    // Library scanning
    // -------------------------------------------------------------------------

    /**
     * Scans DOWNLOAD_TV_SHOWS_PATH and returns directory names that are not yet monitored.
     */
    public List<String> scanUnmonitoredDirectories() {
        Path base = Paths.get(properties.getDownload().getTvShowsPath());
        if (!Files.isDirectory(base)) {
            return List.of();
        }

        List<String> monitored = StreamSupport
                .stream(repository.findAll().spliterator(), false)
                .map(MonitoredShowRecord::getDirectoryName)
                .toList();

        try (var stream = Files.list(base)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !monitored.contains(name))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to scan TV shows directory: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns a list of show directories (both monitored and unmonitored) with episode counts.
     */
    public List<LibraryEntry> scanLibrary() {
        Path base = Paths.get(properties.getDownload().getTvShowsPath());
        List<MonitoredShow> monitoredShows = listAll();

        if (!Files.isDirectory(base)) {
            return monitoredShows.stream()
                    .map(s -> new LibraryEntry(s.getDirectoryName(), 0, 0, s))
                    .toList();
        }

        List<LibraryEntry> entries = new ArrayList<>();

        // Scan filesystem
        try (var stream = Files.list(base)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                String dirName = dir.getFileName().toString();
                ShowDirectoryScanner.Scan scan = ShowDirectoryScanner.scan(dir);
                MonitoredShow monitored = monitoredShows.stream()
                        .filter(s -> s.getDirectoryName().equals(dirName))
                        .findFirst()
                        .orElse(null);
                entries.add(new LibraryEntry(dirName, scan.seasonCount(), scan.episodeCount(), monitored));
            }
        } catch (IOException e) {
            log.error("Failed to scan TV shows directory: {}", e.getMessage());
        }

        // Add monitored shows whose directories no longer exist
        for (MonitoredShow show : monitoredShows) {
            boolean alreadyListed = entries.stream()
                    .anyMatch(e -> e.directoryName().equals(show.getDirectoryName()));
            if (!alreadyListed) {
                entries.add(new LibraryEntry(show.getDirectoryName(), 0, 0, show));
            }
        }

        return entries;
    }

    // -------------------------------------------------------------------------
    // Monitoring check
    // -------------------------------------------------------------------------

    /**
     * Checks a single show for new episodes and enqueues any that are missing.
     * Returns the number of new episodes enqueued.
     */
    public int checkForNewEpisodes(MonitoredShow show) {
        log.info("Checking for new episodes: {} (source={})", show.getTitle(), show.getSource());

        Path base = Paths.get(properties.getDownload().getTvShowsPath());
        Path showDir = base.resolve(show.getDirectoryName());

        // Feature: disable monitoring if the directory has been deleted
        if (!Files.isDirectory(showDir)) {
            log.warn("Show directory no longer exists for '{}' ({}), disabling monitoring",
                    show.getTitle(), showDir);
            setEnabled(show.getId(), false);
            return 0;
        }

        ContentMetadata content = buildContentMetadata(show);
        MediaSourceProvider provider;
        try {
            provider = sourceRegistry.get(show.getSource());
        } catch (IllegalStateException e) {
            log.warn("No provider for source {}, skipping show '{}'", show.getSource(), show.getTitle());
            return 0;
        }

        List<EpisodeRef> episodes;
        try {
            episodes = provider.listEpisodes(content);
        } catch (Exception e) {
            log.error("Failed to list episodes for '{}': {}", show.getTitle(), e.getMessage());
            return 0;
        }

        if (episodes.isEmpty()) {
            log.debug("No episodes returned for '{}'", show.getTitle());
            updateCheckTimestamp(show, null);
            return 0;
        }

        // Feature: only enqueue episodes that come AFTER the latest one already on disk.
        // This prevents filling gaps in a back-catalogue the user intentionally skipped.
        ShowDirectoryScanner.Scan onDisk = ShowDirectoryScanner.scan(showDir);
        EpisodeKey latestOnDisk = onDisk.latest().orElse(null);
        if (latestOnDisk != null) {
            log.info("Latest episode on disk for '{}': S{}E{} — will only enqueue subsequent episodes",
                    show.getTitle(), latestOnDisk.season(), latestOnDisk.episode());
        } else {
            log.info("No episodes found on disk for '{}' — skipping enqueue to avoid downloading entire back-catalogue",
                    show.getTitle());
            updateCheckTimestamp(show, null);
            return 0;
        }

        List<String> defaultLanguages = properties.getDownload().getDefaultLanguageList();
        if (!provider.supportedLanguages().isEmpty()
                && !provider.supportedLanguages().contains(properties.getDownload().getDefaultLanguage())) {
            defaultLanguages = List.of(provider.supportedLanguages().iterator().next());
        }
        String quality = properties.getDownload().getDefaultQuality();
        String preferredLanguage = defaultLanguages.get(0);
        LocalDate today = LocalDate.now();

        int enqueued = 0;
        for (EpisodeRef ep : episodes) {
            // Skip anything not strictly after the latest on-disk episode
            if (!isAfter(ep, latestOnDisk)) {
                continue;
            }
            if (onDisk.contains(ep.season(), ep.episode())) {
                log.debug("Already present: {} S{}E{}", show.getTitle(), ep.season(), ep.episode());
                continue;
            }
            if (isAlreadyQueued(show, ep)) {
                log.debug("Already queued: {} S{}E{}", show.getTitle(), ep.season(), ep.episode());
                continue;
            }

            // Not out in the preferred language yet (VixSrc gets the Italian dub a few days after
            // the original): wait, and stop here so later episodes don't overtake it — once a
            // later episode is on disk, this one would never be picked up.
            if (waitForLanguage(provider, content, ep, preferredLanguage, today)) {
                break;
            }

            ContentMetadata episodeMeta = ContentMetadata.builder()
                    .source(show.getSource())
                    .sourceMetadata(ep.sourceMetadata())
                    .tmdbId(show.getTmdbId())
                    .title(show.getTitle())
                    .episodeName(ep.name())
                    .year(show.getYear())
                    .season(ep.season())
                    .episode(ep.episode())
                    .build();

            try {
                downloadQueueService.enqueueEpisode(episodeMeta, defaultLanguages, quality);
                log.info("Enqueued new episode: {} S{}E{} - {}", show.getTitle(),
                        ep.season(), ep.episode(), ep.name());
                enqueued++;
            } catch (Exception e) {
                log.error("Failed to enqueue episode {} S{}E{}: {}", show.getTitle(),
                        ep.season(), ep.episode(), e.getMessage());
            }
        }

        updateCheckTimestamp(show, enqueued > 0 ? LocalDateTime.now() : null);
        log.info("Check complete for '{}': {} new episode(s) enqueued", show.getTitle(), enqueued);
        return enqueued;
    }

    /**
     * True when {@code ep} has no audio in {@code language} yet and is younger than
     * {@code languageWaitDays}. Episodes the source can't describe, or without an air
     * date, are not held back.
     */
    private boolean waitForLanguage(MediaSourceProvider provider, ContentMetadata show, EpisodeRef ep,
                                    String language, LocalDate today) {
        if (!provider.supportedLanguages().isEmpty() && !provider.supportedLanguages().contains(language)) {
            return false;
        }
        Optional<MediaInfo> info;
        try {
            info = provider.mediaInfo(show, ep.season(), ep.episode());
        } catch (Exception e) {
            log.warn("Could not read audio languages of {} S{}E{}: {}", show.getTitle(), ep.season(), ep.episode(),
                    e.getMessage());
            return false;
        }
        if (info.isEmpty() || info.get().hasAudio(language)) {
            return false;
        }
        int waitDays = properties.getMonitoring().getLanguageWaitDays();
        if (ep.airDate() == null || !ep.airDate().plusDays(waitDays).isAfter(today)) {
            log.info("{} S{}E{} still has no '{}' audio {} days after airing: downloading it as it is",
                    show.getTitle(), ep.season(), ep.episode(), language, waitDays);
            return false;
        }
        log.info("Waiting for '{}' audio: {} S{}E{} (aired {}, downloaded anyway from {})",
                language, show.getTitle(), ep.season(), ep.episode(), ep.airDate(), ep.airDate().plusDays(waitDays));
        return true;
    }

    /** Returns true if {@code ep} comes strictly after {@code anchor} in watch order. */
    private boolean isAfter(EpisodeRef ep, EpisodeKey anchor) {
        return ep.season() > anchor.season()
                || (ep.season() == anchor.season() && ep.episode() > anchor.episode());
    }

    private boolean isAlreadyQueued(MonitoredShow show, EpisodeRef ep) {
        return downloadQueueService.getAllTasks().stream()
                .filter(t -> t.getContentType() == DownloadTask.ContentType.TV)
                .filter(t -> t.getStatus() != DownloadStatus.COMPLETED
                        && t.getStatus() != DownloadStatus.FAILED
                        && t.getStatus() != DownloadStatus.CANCELLED)
                .anyMatch(t -> {
                    boolean titleMatch = show.getTitle().equalsIgnoreCase(t.getTitle());
                    boolean seasonMatch = ep.season() == (t.getSeason() != null ? t.getSeason() : -1);
                    boolean episodeMatch = ep.episode() == (t.getEpisode() != null ? t.getEpisode() : -1);
                    return titleMatch && seasonMatch && episodeMatch;
                });
    }

    private void updateCheckTimestamp(MonitoredShow show, LocalDateTime newEpisodeAt) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastNewEpisode = newEpisodeAt != null ? newEpisodeAt : show.getLastNewEpisodeAt();
        repository.updateCheckTimestamps(show.getId(), now, lastNewEpisode);
        show.setLastCheckedAt(now);
        if (newEpisodeAt != null) {
            show.setLastNewEpisodeAt(newEpisodeAt);
        }
    }

    private ContentMetadata buildContentMetadata(MonitoredShow show) {
        return ContentMetadata.builder()
                .source(show.getSource())
                .sourceMetadata(show.getSourceMetadata())
                .contentType(DownloadTask.ContentType.TV)
                .tmdbId(show.getTmdbId())
                .title(show.getTitle())
                .year(show.getYear())
                .build();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private MonitoredShow fromRecord(MonitoredShowRecord r) {
        return MonitoredShow.builder()
                .id(r.getId())
                .title(r.getTitle())
                .year(r.getYear())
                .tmdbId(r.getTmdbId())
                .source(r.getSource() != null ? MediaSource.valueOf(r.getSource()) : MediaSource.VIXSRC)
                .sourceMetadata(deserializeSourceMetadata(r.getSourceMetadata()))
                .directoryName(r.getDirectoryName())
                .enabled(r.getEnabled() == 1)
                .lastCheckedAt(r.getLastCheckedAt())
                .lastNewEpisodeAt(r.getLastNewEpisodeAt())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private MonitoredShowRecord toRecord(MonitoredShow show) {
        return new MonitoredShowRecord(
                show.getId(),
                show.getTitle(),
                show.getYear(),
                show.getTmdbId(),
                show.getSource() != null ? show.getSource().name() : MediaSource.VIXSRC.name(),
                serializeSourceMetadata(show.getSourceMetadata()),
                show.getDirectoryName(),
                show.isEnabled() ? 1 : 0,
                show.getLastCheckedAt(),
                show.getLastNewEpisodeAt(),
                show.getCreatedAt()
        );
    }

    private String serializeSourceMetadata(SourceMetadata metadata) {
        if (metadata == null) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize SourceMetadata", e);
        }
    }

    private SourceMetadata deserializeSourceMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, SourceMetadata.class);
        } catch (JacksonException e) {
            log.error("Failed to deserialize SourceMetadata: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record LibraryEntry(
            String directoryName,
            int seasonCount,
            int episodeCount,
            MonitoredShow monitoredShow
    ) {
        public boolean isMonitored() {
            return monitoredShow != null;
        }
    }
}
