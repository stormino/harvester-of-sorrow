package com.github.stormino.service;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.persistence.TaskPersistenceService;
import com.github.stormino.model.source.LinkMetadata;
import com.github.stormino.service.link.LinkDownloadService;
import com.github.stormino.service.link.LinkProvider;
import com.github.stormino.service.link.LinkProviderReadyEvent;
import com.github.stormino.service.link.LinkProviderRegistry;
import com.github.stormino.service.link.LinkProviderStatusEvent;
import com.github.stormino.service.link.LinkTaskSpec;
import com.github.stormino.service.link.archive.ArchiveFormats;
import com.github.stormino.service.link.ProviderStatus;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.util.DownloadConstants;
import com.github.stormino.util.LibraryPaths;
import com.github.stormino.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DownloadQueueService {

    private final MediaSourceRegistry sourceRegistry;
    private final TmdbMetadataService metadataService;
    private final DownloadExecutorService executorService;
    private final TrackDownloadOrchestrator trackOrchestrator;
    private final ProgressBroadcastService progressBroadcast;
    private final AppProperties properties;
    private final TaskPersistenceService persistenceService;
    private final LinkProviderRegistry linkProviders;
    private final LinkDownloadService linkDownloadService;

    @Lazy
    @Autowired
    private DownloadQueueService self;

    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Queue<DownloadTask> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public DownloadQueueService(MediaSourceRegistry sourceRegistry,
                                TmdbMetadataService metadataService,
                                DownloadExecutorService executorService,
                                TrackDownloadOrchestrator trackOrchestrator,
                                ProgressBroadcastService progressBroadcast,
                                AppProperties properties,
                                TaskPersistenceService persistenceService,
                                LinkProviderRegistry linkProviders,
                                LinkDownloadService linkDownloadService) {
        this.sourceRegistry = sourceRegistry;
        this.metadataService = metadataService;
        this.executorService = executorService;
        this.trackOrchestrator = trackOrchestrator;
        this.progressBroadcast = progressBroadcast;
        this.properties = properties;
        this.persistenceService = persistenceService;
        this.linkProviders = linkProviders;
        this.linkDownloadService = linkDownloadService;
    }

    /**
     * On startup, restore tasks from the database.
     * In-flight tasks (interrupted by the restart) are marked as FAILED.
     * QUEUED tasks are re-added to the queue.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreFromDatabase() {
        log.info("Restoring download queue from database...");

        List<DownloadStatus> inFlight = List.of(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.EXTRACTING,
                DownloadStatus.MERGING,
                DownloadStatus.UNPACKING,
                DownloadStatus.COPYING
        );

        List<DownloadTask> storedTasks = persistenceService.loadAll();

        for (DownloadTask task : storedTasks) {
            if (inFlight.contains(task.getStatus()) && task.getSource() == MediaSource.LINK) {
                // Link downloads resume from the partial data in their work directory.
                task.setStatus(DownloadStatus.QUEUED);
                task.setProgress(0.0);
                persistenceService.updateTaskStatus(task);
            } else if (inFlight.contains(task.getStatus())) {
                task.setStatus(DownloadStatus.FAILED);
                task.setErrorMessage("Application was restarted during download");
                persistenceService.updateTaskStatus(task);
            }
            tasks.put(task.getId(), task);
            if (task.getStatus() == DownloadStatus.QUEUED) {
                queue.offer(task);
            }
        }

        log.info("Restored {} tasks ({} re-queued)", storedTasks.size(),
                storedTasks.stream().filter(t -> t.getStatus() == DownloadStatus.QUEUED).count());

        if (!queue.isEmpty()) {
            processQueue();
        }
    }
    
    /**
     * Queue a download request and return every task it created.
     *
     * <ul>
     *   <li>Movie: one task.</li>
     *   <li>TV, no seasons: the whole show (episodes the source really has).</li>
     *   <li>TV, seasons but no episodes: each selected season in full.</li>
     *   <li>TV, seasons and episodes: the (season, episode) pairs.</li>
     * </ul>
     * TV episodes are expanded via {@link MediaSourceProvider#listEpisodes} so each
     * task carries the episode's own source metadata and name.
     */
    public List<DownloadTask> enqueue(ContentMetadata content, DownloadTask.ContentType contentType,
                                      List<Integer> seasons, List<Integer> episodes,
                                      List<String> languages, String quality) {
        List<String> langs = (languages == null || languages.isEmpty())
                ? properties.getDownload().getDefaultLanguageList() : List.copyOf(languages);
        String q = (quality == null || quality.isBlank()) ? properties.getDownload().getDefaultQuality() : quality;
        List<Integer> seasonSel = seasons == null ? List.of() : seasons;
        List<Integer> episodeSel = episodes == null ? List.of() : episodes;

        List<DownloadTask> created;
        if (contentType == DownloadTask.ContentType.MOVIE) {
            created = List.of(createMovieTask(content, langs, q));
        } else {
            created = enqueueTv(content, seasonSel, episodeSel, langs, q);
        }
        log.info("Queued {} task(s) for '{}' ({})", created.size(), content.getTitle(), content.getSource());
        processQueue();
        return created;
    }

    /**
     * Queue files of a resolved link, one task each. The library path follows the usual naming
     * (title, year, season folders) but keeps the file's own extension.
     */
    public List<DownloadTask> enqueueLinks(List<LinkTaskSpec> specs) {
        List<DownloadTask> created = new ArrayList<>();
        for (LinkTaskSpec spec : specs) {
            boolean tv = spec.contentType() == DownloadTask.ContentType.TV;
            if (spec.title() == null || spec.title().isBlank()) {
                throw new IllegalArgumentException("A title is required for " + spec.file().fileName());
            }
            boolean archive = ArchiveFormats.isArchive(spec.file().fileName());
            // Archives may hold a whole season: their episodes are read from the names inside.
            if (tv && !archive && (spec.season() == null || spec.episode() == null)) {
                throw new IllegalArgumentException("Season and episode are required for " + spec.file().fileName());
            }
            List<LinkMetadata.Part> parts = spec.parts() == null || spec.parts().isEmpty() ? null
                    : spec.parts().stream().map(p -> new LinkMetadata.Part(p.url(), p.fileName(), p.size())).toList();
            DownloadTask task = DownloadTask.builder()
                    .source(MediaSource.LINK)
                    .sourceMetadata(new LinkMetadata(spec.providerId(), spec.link(), spec.file().url(),
                            spec.file().fileName(), spec.file().size(), parts))
                    .contentType(spec.contentType())
                    .tmdbId(spec.tmdbId())
                    .title(spec.title().strip())
                    .year(spec.year())
                    .season(tv ? spec.season() : null)
                    .episode(tv ? spec.episode() : null)
                    .episodeName(tv ? blankToNull(spec.episodeName()) : null)
                    .languages(properties.getDownload().getDefaultLanguageList())
                    .totalBytes(spec.file().size())
                    .build();
            String path;
            if (tv && (task.getSeason() == null || task.getEpisode() == null)) {
                // Season pack: each episode's path is decided after unpacking.
                path = LibraryPaths.tvShowDir(properties.getDownload().getTvShowsPath(), task.getTitle(), task.getYear());
            } else {
                path = tv ? generateOutputPathFast(task)
                        : generateOutputPath(task, ContentMetadata.builder().title(task.getTitle()).year(task.getYear()).build());
                // Archives: the real extension is the video's, known once unpacked.
                path = archive ? path : withExtension(path, spec.file().fileName());
            }
            task.setOutputPath(path);
            register(task);
            created.add(task);
        }
        log.info("Queued {} link task(s)", created.size());
        processQueue();
        return created;
    }

    /** Replaces the extension of {@code path} with the one of {@code fileName} (if it has one). */
    static String withExtension(String path, String fileName) {
        return LibraryPaths.withExtension(path, fileName);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /**
     * Queue a single, fully resolved TV episode (used by monitoring). No source lookups.
     */
    public DownloadTask enqueueEpisode(ContentMetadata episodeMeta, List<String> languages, String quality) {
        DownloadTask task = createEpisodeTask(episodeMeta, episodeMeta.getSeason(), episodeMeta.getEpisode(),
                episodeMeta.getEpisodeName(), episodeMeta.getSourceMetadata(), languages, quality);
        processQueue();
        return task;
    }

    private List<DownloadTask> enqueueTv(ContentMetadata show, List<Integer> seasons, List<Integer> episodes,
                                         List<String> languages, String quality) {
        if (!episodes.isEmpty() && seasons.size() != 1) {
            throw new IllegalArgumentException("Episodes can only be selected together with exactly one season");
        }
        MediaSourceProvider provider = sourceRegistry.get(show.getSource());
        Integer singleSeason = seasons.size() == 1 ? seasons.get(0) : null;

        List<EpisodeRef> available;
        try {
            available = provider.listEpisodes(show, singleSeason);
        } catch (Exception e) {
            log.warn("Episode listing failed for '{}': {}", show.getTitle(), e.getMessage());
            available = List.of();
        }

        List<DownloadTask> created = new ArrayList<>();
        if (!episodes.isEmpty()) {
            int season = singleSeason;
            for (Integer ep : episodes) {
                EpisodeRef ref = available.stream()
                        .filter(e -> e.season() == season && e.episode() == ep)
                        .findFirst().orElse(null);
                if (ref != null) {
                    created.add(createEpisodeTask(show, season, ep, ref.name(), ref.sourceMetadata(), languages, quality));
                } else {
                    // Not in the listing (or listing failed): queue directly with show-level metadata;
                    // providers resolve season/episode from it at download time.
                    created.add(createEpisodeTask(show, season, ep, null, fallbackEpisodeMetadata(show, season, ep),
                            languages, quality));
                }
            }
            return created;
        }

        List<EpisodeRef> selected = available.stream()
                .filter(e -> seasons.isEmpty() || seasons.contains(e.season()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No episodes found on " + show.getSource().getDisplayName()
                    + " for '" + show.getTitle() + "'" + (seasons.isEmpty() ? "" : " season(s) " + seasons));
        }
        for (EpisodeRef ref : selected) {
            created.add(createEpisodeTask(show, ref.season(), ref.episode(), ref.name(), ref.sourceMetadata(),
                    languages, quality));
        }
        return created;
    }

    private static com.github.stormino.model.source.SourceMetadata fallbackEpisodeMetadata(
            ContentMetadata show, int season, int episode) {
        if (show.getSource() == MediaSource.VIXSRC && show.getTmdbId() != null) {
            return new VixSrcMetadata(show.getTmdbId(), season, episode);
        }
        return show.getSourceMetadata();
    }

    private DownloadTask createMovieTask(ContentMetadata content, List<String> languages, String quality) {
        ContentMetadata metadata = content;
        Integer tmdbId = content.getTmdbId();
        com.github.stormino.model.source.SourceMetadata sourceMetadata = content.getSourceMetadata();
        if (content.getSource() == MediaSource.VIXSRC && tmdbId != null) {
            sourceMetadata = new VixSrcMetadata(tmdbId, null, null);
            if (content.getTitle() == null) {
                metadata = metadataService.getMovieMetadata(tmdbId).orElse(content);
            }
        }
        DownloadTask task = DownloadTask.builder()
                .source(content.getSource() != null ? content.getSource() : MediaSource.VIXSRC)
                .sourceMetadata(sourceMetadata)
                .contentType(DownloadTask.ContentType.MOVIE)
                .tmdbId(tmdbId)
                .title(metadata.getTitle())
                .year(metadata.getYear())
                .languages(languages)
                .quality(quality)
                .build();
        task.setOutputPath(generateOutputPath(task, ContentMetadata.builder()
                .title(metadata.getTitle()).year(metadata.getYear()).build()));
        register(task);
        return task;
    }

    private DownloadTask createEpisodeTask(ContentMetadata show, int season, int episode, String episodeName,
                                           com.github.stormino.model.source.SourceMetadata sourceMetadata,
                                           List<String> languages, String quality) {
        DownloadTask task = DownloadTask.builder()
                .source(show.getSource() != null ? show.getSource() : MediaSource.VIXSRC)
                .sourceMetadata(sourceMetadata)
                .contentType(DownloadTask.ContentType.TV)
                .tmdbId(show.getTmdbId())
                .season(season)
                .episode(episode)
                .title(show.getTitle())
                .episodeName(episodeName)
                .year(show.getYear())
                .languages(languages != null && !languages.isEmpty() ? languages : properties.getDownload().getDefaultLanguageList())
                .quality(quality != null ? quality : properties.getDownload().getDefaultQuality())
                .build();
        task.setOutputPath(generateOutputPathFast(task));
        register(task);
        return task;
    }

    /** Store, persist, queue and announce a new task. */
    private void register(DownloadTask task) {
        tasks.put(task.getId(), task);
        queue.offer(task);
        persistenceService.saveTask(task);
        progressBroadcast.taskCreated(task.getId());
        log.debug("Added download task: {} [{}]", task.getDisplayName(), task.getId());
    }

    /**
     * Fast path generation without metadata object
     */
    private String generateOutputPathFast(DownloadTask task) {
        String basePath = task.getContentType() == DownloadTask.ContentType.TV
                ? properties.getDownload().getTvShowsPath()
                : properties.getDownload().getMoviesPath();
        String filename;

        if (task.getContentType() == DownloadTask.ContentType.TV && task.getTitle() != null) {
            // Folders are created when the finished file is copied (FileCopyService), so a
            // failed or cancelled download leaves no empty show folder in the library.
            return LibraryPaths.tvEpisode(basePath, task.getTitle(), task.getYear(), task.getSeason(),
                    task.getEpisode(), task.getEpisodeName(), DownloadConstants.VIDEO_EXTENSION);
        } else {
            filename = String.format("tv_%d_s%02de%02d.mp4",
                task.getTmdbId(), task.getSeason(), task.getEpisode());
            return java.nio.file.Paths.get(basePath, filename).toString();
        }
    }
    
    /**
     * Get all tasks
     */
    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    /**
     * Get task by ID
     */
    public Optional<DownloadTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
    
    /** Outcome of a cancel/retry request. */
    public enum ActionResult { OK, NOT_FOUND, INVALID_STATE }

    /** Terminal states for a parent task. */
    public static boolean isTerminal(DownloadStatus status) {
        return status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED
                || status == DownloadStatus.CANCELLED || status == DownloadStatus.NOT_FOUND;
    }

    /** Tasks can be cancelled in any non-terminal state, including QUEUED. */
    public static boolean canCancel(DownloadStatus status) {
        return !isTerminal(status);
    }

    /**
     * Tasks can be retried when FAILED or CANCELLED. NOT_FOUND is only ever set on
     * sub-tasks (a missing optional track), so it is not retryable on a parent task.
     */
    public static boolean canRetry(DownloadStatus status) {
        return status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED;
    }

    /**
     * Cancel a task
     */
    public ActionResult cancelTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) {
            return ActionResult.NOT_FOUND;
        }
        if (!canCancel(task.getStatus())) {
            return ActionResult.INVALID_STATE;
        }
        task.setStatus(DownloadStatus.CANCELLED);
        queue.remove(task);
        persistenceService.updateTaskStatus(task);

        // Kill the running process if it exists
        executorService.cancelDownload(taskId);
        linkDownloadService.cancel(taskId);

        progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.CANCELLED)
                .message("Download cancelled by user")
                .build());

        log.info("Cancelled task: {}", task.getDisplayName());
        return ActionResult.OK;
    }

    /**
     * Retry a failed or cancelled task by resetting it to QUEUED and re-scheduling it.
     */
    public ActionResult retryTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null) {
            return ActionResult.NOT_FOUND;
        }
        if (!canRetry(task.getStatus())) {
            return ActionResult.INVALID_STATE;
        }

        task.setStatus(DownloadStatus.QUEUED);
        task.setProgress(0.0);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setDownloadSpeed(null);
        task.setEtaSeconds(null);
        task.setBitrate(null);
        task.getSubTasks().clear();

        persistenceService.resetForRetry(task);

        queue.offer(task);

        progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.QUEUED)
                .progress(0.0)
                .message("Retry queued")
                .build());

        log.info("Retrying task: {} [{}]", task.getDisplayName(), taskId);
        processQueue();
        return ActionResult.OK;
    }

    /**
     * Clear all tasks in final states (COMPLETED, FAILED, CANCELLED, NOT_FOUND)
     */
    public int clearCompletedTasks() {
        List<DownloadTask> toRemove = tasks.values().stream()
                .filter(task -> isTerminal(task.getStatus()))
                .toList();

        toRemove.forEach(t -> tasks.remove(t.getId()));
        toRemove.stream()
                .filter(t -> t.getSource() == MediaSource.LINK)
                .forEach(t -> linkDownloadService.deleteWorkDir(t.getId()));
        persistenceService.deleteCompleted();
        toRemove.forEach(progressBroadcast::taskRemoved);
        log.info("Cleared {} completed tasks", toRemove.size());

        return toRemove.size();
    }

    /**
     * Starts queued tasks while download slots are free. Link tasks additionally wait until their
     * provider is ready (e.g. Mega's proxy pool is warmed up; the warm-up is started here) and
     * respect the provider's own concurrency limit; tasks behind them are not blocked.
     */
    public synchronized void processQueue() {
        int maxParallel = properties.getDownload().getParallelDownloads();
        long activeCount = tasks.values().stream()
                .filter(t -> t.getStatus() == DownloadStatus.DOWNLOADING ||
                            t.getStatus() == DownloadStatus.EXTRACTING)
                .count();
        Map<String, Long> activeLinks = new HashMap<>();
        tasks.values().stream()
                .filter(t -> t.getSource() == MediaSource.LINK && t.isActive())
                .forEach(t -> activeLinks.merge(linkProviderId(t), 1L, Long::sum));

        Iterator<DownloadTask> it = queue.iterator();
        while (activeCount < maxParallel && it.hasNext()) {
            DownloadTask task = it.next();
            if (task.getStatus() != DownloadStatus.QUEUED) {
                it.remove();
                continue;
            }
            if (task.getSource() == MediaSource.LINK) {
                String providerId = linkProviderId(task);
                LinkProvider provider = linkProviders.find(providerId).orElse(null);
                if (provider == null) {
                    it.remove();
                    updateTaskStatus(task, DownloadStatus.FAILED, 0.0,
                            "Link provider '" + providerId + "' is not available");
                    continue;
                }
                ProviderStatus status = provider.status();
                if (!status.isReady()) {
                    provider.prepare();
                    setWaiting(task, provider, provider.status());
                    continue;
                }
                if (activeLinks.getOrDefault(providerId, 0L) >= provider.maxConcurrent()) {
                    setWaiting(task, provider, null);
                    continue;
                }
                activeLinks.merge(providerId, 1L, Long::sum);
                // Claim the slot now: the async worker sets DOWNLOADING only later.
                task.setStatus(DownloadStatus.EXTRACTING);
                task.setStatusMessage(null);
            }
            it.remove();
            activeCount++;
            self.processTaskAsync(task);
        }
    }

    private static String linkProviderId(DownloadTask task) {
        return task.getSourceMetadata() instanceof LinkMetadata m ? m.providerId() : null;
    }

    /** Shows on a queued link task what it is waiting for; broadcasts only when the text changes. */
    private void setWaiting(DownloadTask task, LinkProvider provider, ProviderStatus status) {
        String message;
        if (status == null) {
            message = "Waiting for a free " + provider.displayName() + " slot";
        } else if (status.state() == ProviderStatus.State.ERROR) {
            message = provider.displayName() + ": " + status.message() + " (retrying)";
        } else {
            message = "Waiting for " + provider.displayName()
                    + (status.message() != null ? ": " + status.message() : "");
        }
        if (!message.equals(task.getStatusMessage())) {
            task.setStatusMessage(message);
            progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                    .taskId(task.getId()).status(task.getStatus()).message(message).build());
        }
    }

    @EventListener
    public void onLinkProviderReady(LinkProviderReadyEvent event) {
        log.info("Link provider '{}' is ready", event.providerId());
        processQueue();
    }

    @EventListener
    public void onLinkProviderStatus(LinkProviderStatusEvent event) {
        LinkProvider provider = linkProviders.find(event.providerId()).orElse(null);
        if (provider == null) {
            return;
        }
        for (DownloadTask task : queue) {
            if (task.getStatus() == DownloadStatus.QUEUED && event.providerId().equals(linkProviderId(task))) {
                setWaiting(task, provider, event.status());
            }
        }
    }

    /** Retries a failed provider preparation (e.g. a warm-up) while link tasks are waiting for it. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void retryWaitingLinkProviders() {
        boolean waiting = queue.stream().anyMatch(t -> t.getSource() == MediaSource.LINK
                && t.getStatus() == DownloadStatus.QUEUED);
        if (waiting) {
            processQueue();
        }
    }

    @Async("downloadExecutor")
    public void processTaskAsync(DownloadTask task) {
        log.info("Processing task: {} [{}]", task.getDisplayName(), task.getId());

        try {
            if (task.getSource() == MediaSource.LINK) {
                runLinkTask(task);
                return;
            }

            // Update status
            updateTaskStatus(task, DownloadStatus.EXTRACTING, 0.0, "Extracting playlist URL");

            // Extract playlist URL for primary language
            String primaryLang = task.getLanguages().get(0);
            MediaSourceProvider provider = sourceRegistry.get(task.getSource());
            Optional<PlaylistInfo> playlistInfo = provider.getPlaylist(task, primaryLang);

            if (playlistInfo.isEmpty()) {
                updateTaskStatus(task, DownloadStatus.FAILED, 0.0, "Failed to extract playlist URL");
                return;
            }

            if (task.getStatus() == DownloadStatus.CANCELLED) {
                log.info("Task {} was cancelled during extraction; not starting the download", task.getId());
                return;
            }
            task.setPlaylistUrl(playlistInfo.get().getUrl());
            persistenceService.updateTaskPlaylistUrl(task);

            // Always use track orchestrator for concurrent downloads
            downloadWithTracks(task);

        } catch (Exception e) {
            log.error("Error processing task {}: {}", task.getId(), e.getMessage(), e);
            updateTaskStatus(task, DownloadStatus.FAILED, 0.0, "Error: " + e.getMessage());
        } finally {
            // Trigger queue processing for next task
            processQueue();
        }
    }
    
    private void runLinkTask(DownloadTask task) {
        if (task.getStatus() == DownloadStatus.CANCELLED) {
            return;
        }
        task.setStartedAt(LocalDateTime.now());
        task.setStatusMessage(null);
        updateTaskStatus(task, DownloadStatus.DOWNLOADING, task.getProgress(), "Starting download");

        boolean success = linkDownloadService.run(task);

        task.setDownloadSpeed(null);
        task.setSpeedBps(null);
        task.setEtaSeconds(null);
        if (success) {
            task.setCompletedAt(LocalDateTime.now());
            updateTaskStatus(task, DownloadStatus.COMPLETED, 100.0, "Download completed");
        } else {
            updateTaskStatus(task, DownloadStatus.FAILED, task.getProgress(),
                    task.getErrorMessage() != null ? task.getErrorMessage() : "Download failed");
        }
    }

    private void downloadWithTracks(DownloadTask task) {
        task.setStartedAt(LocalDateTime.now());
        updateTaskStatus(task, DownloadStatus.DOWNLOADING, 0.0, "Starting download");

        // Use track orchestrator for concurrent video/audio downloads
        boolean success = trackOrchestrator.downloadWithTracks(task);

        if (success) {
            task.setCompletedAt(LocalDateTime.now());
            task.setDownloadSpeed(null);
            task.setEtaSeconds(null);
            task.setBitrate(null);
            updateTaskStatus(task, DownloadStatus.COMPLETED, 100.0, "Download completed");
        } else {
            task.setDownloadSpeed(null);
            task.setEtaSeconds(null);
            task.setBitrate(null);
            updateTaskStatus(task, DownloadStatus.FAILED, task.getProgress(),
                    task.getErrorMessage() != null ? task.getErrorMessage() : "Download failed");
        }
    }
    
    private void updateTaskStatus(DownloadTask task, DownloadStatus status, Double progress, String message) {
        // A user cancel wins over whatever the worker thread reports afterwards.
        if (task.getStatus() == DownloadStatus.CANCELLED && status != DownloadStatus.CANCELLED) {
            log.debug("Ignoring {} for cancelled task {}", status, task.getId());
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        if (status == DownloadStatus.FAILED && task.getErrorMessage() == null && message != null) {
            task.setErrorMessage(message);
        }
        persistenceService.updateTaskStatus(task);

        ProgressUpdate update = ProgressUpdate.builder()
                .taskId(task.getId())
                .status(status)
                .progress(progress)
                .downloadedBytes(task.getAggregatedDownloadedBytes())
                .totalBytes(task.getAggregatedTotalBytes())
                .downloadSpeed(task.getAggregatedDownloadSpeed())
                .etaSeconds(task.getAggregatedEtaSeconds())
                .message(message)
                .build();

        progressBroadcast.broadcastProgress(update);
    }
    
    private String generateOutputPath(DownloadTask task, ContentMetadata metadata) {
        String basePath = task.getContentType() == DownloadTask.ContentType.TV
                ? properties.getDownload().getTvShowsPath()
                : properties.getDownload().getMoviesPath();
        String filename;
        
        if (metadata != null) {
            // For TV episodes, always override season/episode/episodeName from the task.
            // The content object is the shared SearchResultCard metadata and may carry
            // stale values from a previous enqueue of a different episode (same card,
            // different user selection). Null-guarding here would cause every episode
            // after the first to inherit the first episode's filename.
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                if (task.getSeason() != null) metadata.setSeason(task.getSeason());
                if (task.getEpisode() != null) metadata.setEpisode(task.getEpisode());
                // Always set from task (may be null if lookup failed); clears any
                // stale episode title the card carried from a different episode.
                metadata.setEpisodeName(task.getEpisodeName());
            }
            filename = metadata.generateFilename(
                    task.getLanguages().get(0),
                    "mp4"
            );
        } else {
            // Fallback filename
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                filename = String.format("tv_%d_s%02de%02d.mp4",
                        task.getTmdbId(), task.getSeason(), task.getEpisode());
            } else {
                filename = String.format("movie_%d.mp4", task.getTmdbId());
            }
        }
        
        // Create directory structure for TV shows
        String outputPath;
        if (task.getContentType() == DownloadTask.ContentType.TV && metadata != null) {
            String showDir = PathUtils.sanitizeFilename(metadata.getTitle());
            if (metadata.getYear() != null) {
                showDir = showDir + "." + metadata.getYear();
            }
            String seasonDir = String.format(DownloadConstants.SEASON_DIR_FORMAT, task.getSeason());
            outputPath = Paths.get(basePath, showDir, seasonDir, filename).toString();
        } else {
            // Movies go directly in base path
            outputPath = Paths.get(basePath, filename).toString();
        }

        return outputPath;
    }
}
