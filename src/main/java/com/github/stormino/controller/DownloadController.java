package com.github.stormino.controller;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.RaiPlayMetadata;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadQueueService downloadQueueService;
    private final TmdbMetadataService metadataService;
    private final MediaSourceRegistry sourceRegistry;

    // -------------------------------------------------------------------------
    // Multi-source search
    // -------------------------------------------------------------------------

    /**
     * Search across all registered sources in parallel.
     *
     * @param query      free-text search term
     * @param type       optional filter: MOVIES | TV | BOTH (default: BOTH)
     * @param source     optional: limit to a specific source (VIXSRC | RAIPLAY)
     */
    @GetMapping("/search")
    public ResponseEntity<List<ContentMetadata>> search(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "BOTH") String type,
            @RequestParam(required = false) String source) {

        ContentTypeFilter filter;
        try {
            filter = ContentTypeFilter.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            filter = ContentTypeFilter.BOTH;
        }

        List<MediaSourceProvider> providers = source != null
                ? List.of(sourceRegistry.get(MediaSource.valueOf(source.toUpperCase())))
                : sourceRegistry.all();

        ContentTypeFilter finalFilter = filter;
        List<CompletableFuture<List<ContentMetadata>>> futures = providers.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.search(query, finalFilter);
                    } catch (Exception e) {
                        log.warn("Search failed for source {}: {}", p.source(), e.getMessage());
                        return List.<ContentMetadata>of();
                    }
                }))
                .collect(Collectors.toList());

        List<ContentMetadata> results = new ArrayList<>();
        for (CompletableFuture<List<ContentMetadata>> future : futures) {
            try {
                results.addAll(future.get());
            } catch (Exception e) {
                log.warn("Search future failed: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(results);
    }

    // -------------------------------------------------------------------------
    // VixSrc-specific search (legacy endpoints kept for backward compatibility)
    // -------------------------------------------------------------------------

    @GetMapping("/search/movies")
    public ResponseEntity<List<ContentMetadata>> searchMovies(@RequestParam String query) {
        log.info("Searching movies (VixSrc/TMDB): {}", query);
        return ResponseEntity.ok(metadataService.searchMovies(query));
    }

    @GetMapping("/search/tv")
    public ResponseEntity<List<ContentMetadata>> searchTv(@RequestParam String query) {
        log.info("Searching TV shows (VixSrc/TMDB): {}", query);
        return ResponseEntity.ok(metadataService.searchTvShows(query));
    }

    // -------------------------------------------------------------------------
    // VixSrc downloads (TMDB-based, legacy)
    // -------------------------------------------------------------------------

    @PostMapping("/download/movie")
    public ResponseEntity<DownloadTask> downloadMovie(
            @RequestParam int tmdbId,
            @RequestParam(required = false) List<String> languages,
            @RequestParam(required = false) String quality) {

        log.info("Adding VixSrc movie download: TMDB ID {}", tmdbId);
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId, DownloadTask.ContentType.MOVIE, null, null, languages, quality);
        return ResponseEntity.ok(task);
    }

    @PostMapping("/download/tv")
    public ResponseEntity<DownloadTask> downloadTv(
            @RequestParam int tmdbId,
            @RequestParam int season,
            @RequestParam int episode,
            @RequestParam(required = false) List<String> languages,
            @RequestParam(required = false) String quality) {

        log.info("Adding VixSrc TV download: TMDB ID {}, S{}E{}", tmdbId, season, episode);
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId, DownloadTask.ContentType.TV, season, episode, languages, quality);
        return ResponseEntity.ok(task);
    }

    // -------------------------------------------------------------------------
    // RaiPlay downloads (pathId-based)
    // -------------------------------------------------------------------------

    /**
     * Queue a RaiPlay movie download.
     *
     * @param pathId  content path from the RaiPlay descriptor, e.g.
     *                {@code /video/2018/12/COSMONAUTA-f5cbe4fd-....json}
     * @param title   display title
     */
    @PostMapping("/download/raiplay/movie")
    public ResponseEntity<DownloadTask> downloadRaiPlayMovie(
            @RequestParam String pathId,
            @RequestParam String title,
            @RequestParam(required = false) Integer year) {

        log.info("Adding RaiPlay movie download: pathId={}", pathId);
        ContentMetadata meta = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(new RaiPlayMetadata(pathId, null, null, null, null))
                .title(title)
                .year(year)
                .build();
        DownloadTask task = downloadQueueService.addDownload(
                meta, DownloadTask.ContentType.MOVIE, null, null, List.of("it"), null, false);
        return ResponseEntity.ok(task);
    }

    /**
     * Queue a RaiPlay episode download.
     *
     * @param pathId  content path from the RaiPlay descriptor
     * @param title   show title
     * @param season  season number
     * @param episode episode number
     */
    @PostMapping("/download/raiplay/tv")
    public ResponseEntity<DownloadTask> downloadRaiPlayTv(
            @RequestParam String pathId,
            @RequestParam String title,
            @RequestParam int season,
            @RequestParam int episode,
            @RequestParam(required = false) String episodeName) {

        log.info("Adding RaiPlay TV download: pathId={} S{}E{}", pathId, season, episode);
        ContentMetadata meta = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(new RaiPlayMetadata(pathId, null, null, String.valueOf(season), null))
                .title(title)
                .season(season)
                .episode(episode)
                .episodeName(episodeName)
                .numberOfSeasons(0)
                .build();
        DownloadTask task = downloadQueueService.addDownload(
                meta, DownloadTask.ContentType.TV, season, episode, List.of("it"), null, false);
        return ResponseEntity.ok(task);
    }

    // -------------------------------------------------------------------------
    // Queue management
    // -------------------------------------------------------------------------

    @GetMapping("/downloads")
    public ResponseEntity<List<DownloadTask>> getAllDownloads() {
        return ResponseEntity.ok(downloadQueueService.getAllTasks());
    }

    @GetMapping("/downloads/{id}")
    public ResponseEntity<DownloadTask> getDownload(@PathVariable String id) {
        return downloadQueueService.getTask(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/downloads/{id}")
    public ResponseEntity<Void> cancelDownload(@PathVariable String id) {
        return downloadQueueService.cancelTask(id)
                ? ResponseEntity.ok().<Void>build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/downloads/{id}/retry")
    public ResponseEntity<Void> retryDownload(@PathVariable String id) {
        return downloadQueueService.retryTask(id)
                ? ResponseEntity.ok().<Void>build()
                : ResponseEntity.notFound().build();
    }
}
