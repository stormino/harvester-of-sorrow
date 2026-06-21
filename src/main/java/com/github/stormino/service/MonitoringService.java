package com.github.stormino.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.persistence.MonitoredShowRecord;
import com.github.stormino.persistence.MonitoredShowRepository;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.util.DownloadConstants;
import com.github.stormino.util.PathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                int[] counts = countSeasonsAndEpisodes(dir);
                MonitoredShow monitored = monitoredShows.stream()
                        .filter(s -> s.getDirectoryName().equals(dirName))
                        .findFirst()
                        .orElse(null);
                entries.add(new LibraryEntry(dirName, counts[0], counts[1], monitored));
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

    private int[] countSeasonsAndEpisodes(Path showDir) {
        int seasons = 0;
        int episodes = 0;
        try (var stream = Files.list(showDir)) {
            List<Path> seasonDirs = stream.filter(Files::isDirectory).toList();
            seasons = seasonDirs.size();
            for (Path seasonDir : seasonDirs) {
                try (var epStream = Files.list(seasonDir)) {
                    episodes += (int) epStream
                            .filter(p -> p.toString().endsWith(DownloadConstants.VIDEO_EXTENSION))
                            .count();
                } catch (IOException e) {
                    log.debug("Could not list season dir {}: {}", seasonDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("Could not list show dir {}: {}", showDir, e.getMessage());
        }
        return new int[]{seasons, episodes};
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
        EpisodeKey latestOnDisk = findLatestEpisodeOnDisk(showDir);
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
        String quality = properties.getDownload().getDefaultQuality();

        int enqueued = 0;
        for (EpisodeRef ep : episodes) {
            // Skip anything not strictly after the latest on-disk episode
            if (!isAfter(ep, latestOnDisk)) {
                continue;
            }
            if (isAlreadyPresent(showDir, show.getTitle(), ep)) {
                log.debug("Already present: {} S{}E{}", show.getTitle(), ep.season(), ep.episode());
                continue;
            }
            if (isAlreadyQueued(show, ep)) {
                log.debug("Already queued: {} S{}E{}", show.getTitle(), ep.season(), ep.episode());
                continue;
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
                downloadQueueService.addDownload(episodeMeta, DownloadTask.ContentType.TV,
                        ep.season(), ep.episode(), defaultLanguages, quality);
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
     * Scans the show directory and returns the highest (season, episode) found on disk,
     * or null if no episodes are present.
     */
    private EpisodeKey findLatestEpisodeOnDisk(Path showDir) {
        EpisodeKey latest = null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("S(\\d+)E(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        try (var seasonStream = Files.list(showDir)) {
            List<Path> seasonDirs = seasonStream.filter(Files::isDirectory).toList();
            for (Path seasonDir : seasonDirs) {
                try (var epStream = Files.list(seasonDir)) {
                    List<Path> files = epStream
                            .filter(p -> p.toString().endsWith(DownloadConstants.VIDEO_EXTENSION))
                            .toList();
                    for (Path file : files) {
                        var matcher = pattern.matcher(file.getFileName().toString());
                        if (matcher.find()) {
                            int s = Integer.parseInt(matcher.group(1));
                            int e = Integer.parseInt(matcher.group(2));
                            if (latest == null || s > latest.season() || (s == latest.season() && e > latest.episode())) {
                                latest = new EpisodeKey(s, e);
                            }
                        }
                    }
                } catch (IOException ex) {
                    log.debug("Could not read season dir {}: {}", seasonDir, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.debug("Could not read show dir {}: {}", showDir, ex.getMessage());
        }
        return latest;
    }

    /** Returns true if {@code ep} comes strictly after {@code anchor} in watch order. */
    private boolean isAfter(EpisodeRef ep, EpisodeKey anchor) {
        return ep.season() > anchor.season()
                || (ep.season() == anchor.season() && ep.episode() > anchor.episode());
    }

    private record EpisodeKey(int season, int episode) {}

    private boolean isAlreadyPresent(Path showDir, String showTitle, EpisodeRef ep) {
        // Check all season subdirectories for a file matching S{nn}E{nn}
        String episodePattern = String.format("S%02dE%02d", ep.season(), ep.episode());
        Path seasonDir = showDir.resolve(String.format(DownloadConstants.SEASON_DIR_FORMAT, ep.season()));
        if (!Files.isDirectory(seasonDir)) {
            return false;
        }
        try (var stream = Files.list(seasonDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().contains(episodePattern)
                    && p.toString().endsWith(DownloadConstants.VIDEO_EXTENSION));
        } catch (IOException e) {
            return false;
        }
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SourceMetadata", e);
        }
    }

    private SourceMetadata deserializeSourceMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, SourceMetadata.class);
        } catch (JsonProcessingException e) {
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
