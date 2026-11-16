package com.github.stormino.service.link;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.model.source.LinkMetadata;
import com.github.stormino.persistence.TaskPersistenceService;
import com.github.stormino.service.FileCopyService;
import com.github.stormino.service.ProgressBroadcastService;
import com.github.stormino.service.link.archive.ArchiveExtractor;
import com.github.stormino.service.link.archive.ArchiveFormats;
import com.github.stormino.service.link.archive.ArchivePlacer;
import com.github.stormino.util.FormatUtils;
import com.github.stormino.util.LibraryPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Runs one {@link com.github.stormino.model.MediaSource#LINK} task: downloads its file through the
 * task's provider into a per-task work directory, then copies it to the library.
 *
 * <p>The work directory ({@code <temp>/links/<task id>}) is kept when a download fails or is
 * cancelled, so a retry (or a restart) resumes from the partial data; it is removed after a
 * successful copy and when the task is cleared.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkDownloadService {

    static final String UNPACKED_DIR = "unpacked";

    private final LinkProviderRegistry providers;
    private final ArchiveExtractor extractor;
    private final LinkMatchService matcher;
    private final AppProperties properties;
    private final FileCopyService fileCopyService;
    private final TaskPersistenceService persistenceService;
    private final ProgressBroadcastService progressBroadcast;

    private final Map<String, LinkControl> running = new ConcurrentHashMap<>();

    /**
     * Downloads and copies the task's file. On failure the task's error message is set.
     *
     * @return {@code true} when the file is in the library
     */
    public boolean run(DownloadTask task) {
        if (!(task.getSourceMetadata() instanceof LinkMetadata meta)) {
            task.setErrorMessage("Missing link metadata");
            return false;
        }
        LinkProvider provider = providers.find(meta.providerId()).orElse(null);
        if (provider == null) {
            task.setErrorMessage("Link provider '" + meta.providerId() + "' is not available");
            return false;
        }
        Path destination = Paths.get(task.getOutputPath());
        Path workDir = workDir(task.getId());
        LinkControl control = new LinkControl();
        running.put(task.getId(), control);
        if (task.getStatus() == DownloadStatus.CANCELLED) {
            control.cancel();
        }
        try {
            Optional<ArchiveFormats.Volume> archive = ArchiveFormats.volume(meta.fileName());
            if (archive.isPresent()) {
                return runArchive(task, meta, archive.get(), provider, workDir, control);
            }
            LinkFile file = new LinkFile(meta.fileUrl(), meta.fileName(), meta.size(), null);
            Path downloaded = provider.download(file, workDir, destination.getFileName().toString(),
                    new TaskProgress(task, 0, meta.size()), control);

            enterStatus(task, DownloadStatus.COPYING);
            fileCopyService.copyWithVerification(downloaded, destination);
            deleteWorkDir(task.getId());
            return true;
        } catch (LinkCancelledException e) {
            log.info("Link download cancelled: {}", task.getDisplayName());
            return false;
        } catch (LinkException e) {
            log.warn("Link download failed for {}: {}", task.getDisplayName(), e.getMessage());
            task.setErrorMessage(e.getMessage());
            return false;
        } catch (IOException e) {
            log.warn("Copy to {} failed: {}", destination, e.getMessage());
            task.setErrorMessage("Failed to copy file to destination: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("Link download crashed for {}", task.getDisplayName(), e);
            task.setErrorMessage("Error: " + e.getMessage());
            return false;
        } finally {
            running.remove(task.getId());
            task.setStatusMessage(null);
        }
    }

    /**
     * Archive: downloads every volume under its own name (the tools find the other volumes by
     * name), unpacks, then copies the videos (and their subtitles) the {@link ArchivePlacer} picks.
     */
    private boolean runArchive(DownloadTask task, LinkMetadata meta, ArchiveFormats.Volume first, LinkProvider provider,
                               Path workDir, LinkControl control) throws IOException {
        List<LinkFile> parts = meta.parts() == null || meta.parts().isEmpty()
                ? List.of(new LinkFile(meta.fileUrl(), meta.fileName(), meta.size(), null))
                : meta.parts().stream().map(p -> new LinkFile(p.url(), p.fileName(), p.size(), null)).toList();
        Long total = parts.stream().allMatch(p -> p.size() != null)
                ? parts.stream().mapToLong(LinkFile::size).sum() : null;
        Path firstVolume = null;
        int firstOrder = Integer.MAX_VALUE;
        long before = 0;
        for (LinkFile part : parts) {
            String name = Paths.get(part.fileName()).getFileName().toString();
            Path path = provider.download(part, workDir, name, new TaskProgress(task, before, total), control);
            before += part.size() != null ? part.size() : 0;
            int order = ArchiveFormats.volume(name).map(ArchiveFormats.Volume::order).orElse(Integer.MAX_VALUE);
            if (order < firstOrder) {
                firstOrder = order;
                firstVolume = path;
            }
        }

        enterStatus(task, DownloadStatus.UNPACKING);
        task.setProgress(0.0);
        Path unpacked = workDir.resolve(UNPACKED_DIR);
        extractor.extract(firstVolume, first.kind(), unpacked, pct -> {
            task.setProgress((double) pct);
            broadcast(task);
        }, control);

        List<ArchivePlacer.Entry> entries;
        try (Stream<Path> walk = Files.walk(unpacked)) {
            entries = walk.filter(Files::isRegularFile)
                    .map(p -> new ArchivePlacer.Entry(unpacked.relativize(p), sizeOf(p)))
                    .toList();
        }
        ArchivePlacer.Plan plan = ArchivePlacer.plan(entries, task.getContentType(),
                ArchiveFormats.baseName(meta.fileName()), task.getSeason(), task.getEpisode(), destinations(task));
        if (plan.videos() == 0) {
            task.setErrorMessage(plan.notes().isEmpty() ? "Nothing to place from the archive"
                    : String.join("; ", plan.notes()));
            return false;
        }

        enterStatus(task, DownloadStatus.COPYING);
        for (ArchivePlacer.Placement p : plan.placements()) {
            if (control.isCancelled()) {
                throw new LinkCancelledException("Cancelled");
            }
            fileCopyService.copyWithVerification(unpacked.resolve(p.source()), Paths.get(p.destination()));
            log.info("Placed {} -> {}", p.source(), p.destination());
        }
        if (task.getContentType() != DownloadTask.ContentType.TV || plan.videos() == 1) {
            plan.placements().stream().findFirst().ifPresent(p -> task.setOutputPath(p.destination()));
        }
        // Completed, but tell the user what was left out (shown as task details).
        task.setErrorMessage(plan.notes().isEmpty() ? null
                : "Placed " + plan.videos() + " video(s). " + String.join("; ", plan.notes()));
        deleteWorkDir(task.getId());
        return true;
    }

    /** Library paths for an archive task: the movie path, or one path per episode. */
    private ArchivePlacer.Destinations destinations(DownloadTask task) {
        Map<Integer, Map<Integer, String>> episodeNames = new HashMap<>();
        return new ArchivePlacer.Destinations() {
            @Override
            public String movie(String extension) {
                return LibraryPaths.withExtension(task.getOutputPath(), "x" + extension);
            }

            @Override
            public String episode(int season, int episode, String extension) {
                String name = null;
                if (task.getSeason() != null && task.getEpisode() != null
                        && task.getSeason() == season && task.getEpisode() == episode) {
                    name = task.getEpisodeName();
                }
                if (name == null && task.getTmdbId() != null) {
                    name = episodeNames.computeIfAbsent(season, s -> matcher.episodeNames(task.getTmdbId(), s))
                            .get(episode);
                }
                return LibraryPaths.tvEpisode(properties.getDownload().getTvShowsPath(), task.getTitle(),
                        task.getYear(), season, episode, name, extension);
            }
        };
    }

    private void enterStatus(DownloadTask task, DownloadStatus status) {
        task.setStatus(status);
        task.setStatusMessage(null);
        task.setDownloadSpeed(null);
        task.setSpeedBps(null);
        task.setEtaSeconds(null);
        persistenceService.updateTaskStatus(task);
        broadcast(task);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Stops a running download of {@code taskId}; no-op when it is not running. */
    public void cancel(String taskId) {
        LinkControl control = running.get(taskId);
        if (control != null) {
            control.cancel();
        }
    }

    /** Removes partial data of a task (when it is cleared from the list). */
    public void deleteWorkDir(String taskId) {
        Path dir = workDir(taskId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Cannot delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Cannot clean up {}: {}", dir, e.getMessage());
        }
    }

    Path workDir(String taskId) {
        return Paths.get(properties.getDownload().getTempPath(), "links", taskId);
    }

    private void broadcast(DownloadTask task) {
        progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .downloadedBytes(task.getDownloadedBytes())
                .totalBytes(task.getTotalBytes())
                .downloadSpeed(task.getDownloadSpeed())
                .etaSeconds(task.getEtaSeconds())
                .build());
    }

    /** Copies provider progress onto the task (link tasks have no sub-tasks). */
    private final class TaskProgress implements LinkProgress {
        private final DownloadTask task;
        /** Bytes of earlier archive volumes, and the size of all volumes (null: per-file size). */
        private final long offset;
        private final Long overallTotal;

        TaskProgress(DownloadTask task, long offset, Long overallTotal) {
            this.task = task;
            this.offset = offset;
            this.overallTotal = overallTotal;
        }

        @Override
        public void update(long fileDone, long fileTotal, double bytesPerSecond) {
            long bytesDone = offset + fileDone;
            long totalBytes = overallTotal != null && overallTotal > 0 ? overallTotal : fileTotal;
            task.setDownloadedBytes(bytesDone);
            if (totalBytes > 0) {
                task.setTotalBytes(totalBytes);
                task.setProgress(Math.min(100.0, bytesDone * 100.0 / totalBytes));
                task.setEtaSeconds(bytesPerSecond > 0 ? (long) ((totalBytes - bytesDone) / bytesPerSecond) : null);
            }
            task.setDownloadSpeed(bytesPerSecond > 0 ? FormatUtils.formatSpeed(bytesPerSecond) : null);
            task.setSpeedBps(bytesPerSecond > 0 ? bytesPerSecond : null);
            if (fileDone > 0 && task.getStatusMessage() != null) {
                task.setStatusMessage(null); // bytes flowing: whatever we were waiting for is over
            }
            broadcast(task);
        }

        @Override
        public void status(String message) {
            if (!java.util.Objects.equals(message, task.getStatusMessage())) {
                task.setStatusMessage(message);
                broadcast(task);
            }
        }
    }
}
