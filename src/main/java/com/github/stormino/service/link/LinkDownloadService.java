package com.github.stormino.service.link;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.model.source.LinkMetadata;
import com.github.stormino.persistence.TaskPersistenceService;
import com.github.stormino.service.FileCopyService;
import com.github.stormino.service.ProgressBroadcastService;
import com.github.stormino.util.FormatUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
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

    private final LinkProviderRegistry providers;
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
            LinkFile file = new LinkFile(meta.fileUrl(), meta.fileName(), meta.size(), null);
            Path downloaded = provider.download(file, workDir, destination.getFileName().toString(),
                    new TaskProgress(task), control);

            task.setStatus(DownloadStatus.COPYING);
            task.setDownloadSpeed(null);
            task.setSpeedBps(null);
            task.setEtaSeconds(null);
            persistenceService.updateTaskStatus(task);
            broadcast(task);
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

        TaskProgress(DownloadTask task) {
            this.task = task;
        }

        @Override
        public void update(long bytesDone, long totalBytes, double bytesPerSecond) {
            task.setDownloadedBytes(bytesDone);
            if (totalBytes > 0) {
                task.setTotalBytes(totalBytes);
                task.setProgress(Math.min(100.0, bytesDone * 100.0 / totalBytes));
                task.setEtaSeconds(bytesPerSecond > 0 ? (long) ((totalBytes - bytesDone) / bytesPerSecond) : null);
            }
            task.setDownloadSpeed(bytesPerSecond > 0 ? FormatUtils.formatSpeed(bytesPerSecond) : null);
            task.setSpeedBps(bytesPerSecond > 0 ? bytesPerSecond : null);
            broadcast(task);
        }
    }
}
