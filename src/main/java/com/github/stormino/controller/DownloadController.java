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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
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

    @Tag(name = "Search")
    @Operation(
        summary = "Search across all sources",
        description = "Search movies and/or TV shows across all registered sources (VixSrc, RaiPlay) in parallel. " +
                      "Results are filtered by availability on each source before being returned."
    )
    @ApiResponse(responseCode = "200", description = "Search results",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = ContentMetadata.class))))
    @GetMapping("/search")
    public ResponseEntity<List<ContentMetadata>> search(
            @Parameter(description = "Free-text search term", required = true, example = "Breaking Bad")
            @RequestParam String query,
            @Parameter(description = "Content type filter", schema = @Schema(allowableValues = {"MOVIES", "TV", "BOTH"}))
            @RequestParam(required = false, defaultValue = "BOTH") String type,
            @Parameter(description = "Limit results to a single source", schema = @Schema(allowableValues = {"VIXSRC", "RAIPLAY"}))
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

    @Tag(name = "Search")
    @Operation(summary = "Search movies (VixSrc/TMDB)", description = "Legacy endpoint. Prefer `/api/search?type=MOVIES`.")
    @ApiResponse(responseCode = "200", description = "Movie search results",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = ContentMetadata.class))))
    @GetMapping("/search/movies")
    public ResponseEntity<List<ContentMetadata>> searchMovies(
            @Parameter(description = "Movie title to search", required = true, example = "Fight Club")
            @RequestParam String query) {
        log.info("Searching movies (VixSrc/TMDB): {}", query);
        return ResponseEntity.ok(metadataService.searchMovies(query));
    }

    @Tag(name = "Search")
    @Operation(summary = "Search TV shows (VixSrc/TMDB)", description = "Legacy endpoint. Prefer `/api/search?type=TV`.")
    @ApiResponse(responseCode = "200", description = "TV show search results",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = ContentMetadata.class))))
    @GetMapping("/search/tv")
    public ResponseEntity<List<ContentMetadata>> searchTv(
            @Parameter(description = "TV show title to search", required = true, example = "Breaking Bad")
            @RequestParam String query) {
        log.info("Searching TV shows (VixSrc/TMDB): {}", query);
        return ResponseEntity.ok(metadataService.searchTvShows(query));
    }

    // -------------------------------------------------------------------------
    // VixSrc downloads (TMDB-based)
    // -------------------------------------------------------------------------

    @Tag(name = "Downloads")
    @Operation(summary = "Queue a VixSrc movie download")
    @ApiResponse(responseCode = "200", description = "Download task created",
        content = @Content(schema = @Schema(implementation = DownloadTask.class)))
    @PostMapping("/download/movie")
    public ResponseEntity<DownloadTask> downloadMovie(
            @Parameter(description = "TMDB ID of the movie", required = true, example = "550")
            @RequestParam int tmdbId,
            @Parameter(description = "Audio language codes, e.g. `en,it`", example = "en")
            @RequestParam(required = false) List<String> languages,
            @Parameter(description = "Video quality: `best`, `1080`, `720`, etc.", example = "1080")
            @RequestParam(required = false) String quality) {

        log.info("Adding VixSrc movie download: TMDB ID {}", tmdbId);
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId, DownloadTask.ContentType.MOVIE, null, null, languages, quality);
        return ResponseEntity.ok(task);
    }

    @Tag(name = "Downloads")
    @Operation(summary = "Queue a VixSrc TV episode download")
    @ApiResponse(responseCode = "200", description = "Download task created",
        content = @Content(schema = @Schema(implementation = DownloadTask.class)))
    @PostMapping("/download/tv")
    public ResponseEntity<DownloadTask> downloadTv(
            @Parameter(description = "TMDB ID of the TV show", required = true, example = "1396")
            @RequestParam int tmdbId,
            @Parameter(description = "Season number", required = true, example = "1")
            @RequestParam int season,
            @Parameter(description = "Episode number", required = true, example = "1")
            @RequestParam int episode,
            @Parameter(description = "Audio language codes", example = "en")
            @RequestParam(required = false) List<String> languages,
            @Parameter(description = "Video quality: `best`, `1080`, `720`, etc.", example = "best")
            @RequestParam(required = false) String quality) {

        log.info("Adding VixSrc TV download: TMDB ID {}, S{}E{}", tmdbId, season, episode);
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId, DownloadTask.ContentType.TV, season, episode, languages, quality);
        return ResponseEntity.ok(task);
    }

    // -------------------------------------------------------------------------
    // RaiPlay downloads (pathId-based)
    // -------------------------------------------------------------------------

    @Tag(name = "Downloads")
    @Operation(
        summary = "Queue a RaiPlay movie download",
        description = "The `pathId` comes from the RaiPlay content descriptor, e.g. `/video/2018/12/COSMONAUTA-f5cbe4fd-....json`."
    )
    @ApiResponse(responseCode = "200", description = "Download task created",
        content = @Content(schema = @Schema(implementation = DownloadTask.class)))
    @PostMapping("/download/raiplay/movie")
    public ResponseEntity<DownloadTask> downloadRaiPlayMovie(
            @Parameter(description = "RaiPlay content path", required = true, example = "/video/2018/12/COSMONAUTA-f5cbe4fd-abc.json")
            @RequestParam String pathId,
            @Parameter(description = "Display title", required = true, example = "Il Cosmonauta")
            @RequestParam String title,
            @Parameter(description = "Release year", example = "2018")
            @RequestParam(required = false) Integer year) {

        log.info("Adding RaiPlay movie download: pathId={}", pathId);
        ContentMetadata meta = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(new RaiPlayMetadata(pathId, null, null, null, null))
                .title(title)
                .year(year)
                .build();
        DownloadTask task = downloadQueueService.addDownload(
                meta, DownloadTask.ContentType.MOVIE, (Integer) null, (Integer) null, List.of("it"), null);
        return ResponseEntity.ok(task);
    }

    @Tag(name = "Downloads")
    @Operation(
        summary = "Queue a RaiPlay TV episode download",
        description = "The `pathId` comes from the RaiPlay episode descriptor."
    )
    @ApiResponse(responseCode = "200", description = "Download task created",
        content = @Content(schema = @Schema(implementation = DownloadTask.class)))
    @PostMapping("/download/raiplay/tv")
    public ResponseEntity<DownloadTask> downloadRaiPlayTv(
            @Parameter(description = "RaiPlay content path", required = true, example = "/programmi/serie/my-show/s1e1.json")
            @RequestParam String pathId,
            @Parameter(description = "Show title", required = true, example = "Boris")
            @RequestParam String title,
            @Parameter(description = "Season number", required = true, example = "1")
            @RequestParam int season,
            @Parameter(description = "Episode number", required = true, example = "1")
            @RequestParam int episode,
            @Parameter(description = "Episode name", example = "Il Pilota")
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
                meta, DownloadTask.ContentType.TV, season, episode, List.of("it"), null);
        return ResponseEntity.ok(task);
    }

    // -------------------------------------------------------------------------
    // Queue management
    // -------------------------------------------------------------------------

    @Tag(name = "Downloads")
    @Operation(summary = "List all download tasks")
    @ApiResponse(responseCode = "200", description = "List of all tasks",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = DownloadTask.class))))
    @GetMapping("/downloads")
    public ResponseEntity<List<DownloadTask>> getAllDownloads() {
        return ResponseEntity.ok(downloadQueueService.getAllTasks());
    }

    @Tag(name = "Downloads")
    @Operation(summary = "Get a download task by ID")
    @ApiResponse(responseCode = "200", description = "Task found",
        content = @Content(schema = @Schema(implementation = DownloadTask.class)))
    @ApiResponse(responseCode = "404", description = "Task not found", content = @Content)
    @GetMapping("/downloads/{id}")
    public ResponseEntity<DownloadTask> getDownload(
            @Parameter(description = "Download task UUID", required = true)
            @PathVariable String id) {
        return downloadQueueService.getTask(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Tag(name = "Downloads")
    @Operation(summary = "Cancel a download task")
    @ApiResponse(responseCode = "200", description = "Cancelled", content = @Content)
    @ApiResponse(responseCode = "404", description = "Task not found", content = @Content)
    @DeleteMapping("/downloads/{id}")
    public ResponseEntity<Void> cancelDownload(
            @Parameter(description = "Download task UUID", required = true)
            @PathVariable String id) {
        return downloadQueueService.cancelTask(id)
                ? ResponseEntity.ok().<Void>build()
                : ResponseEntity.notFound().build();
    }

    @Tag(name = "Downloads")
    @Operation(summary = "Retry a failed or cancelled download task")
    @ApiResponse(responseCode = "200", description = "Re-queued", content = @Content)
    @ApiResponse(responseCode = "404", description = "Task not found", content = @Content)
    @PostMapping("/downloads/{id}/retry")
    public ResponseEntity<Void> retryDownload(
            @Parameter(description = "Download task UUID", required = true)
            @PathVariable String id) {
        return downloadQueueService.retryTask(id)
                ? ResponseEntity.ok().<Void>build()
                : ResponseEntity.notFound().build();
    }
}
