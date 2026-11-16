package com.github.stormino.api;

import com.github.stormino.api.dto.SubTaskDto;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.DownloadQueueService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Maps download domain objects to API DTOs. Timestamps stored as server-local
 * {@link LocalDateTime} become {@link Instant}s; speeds become bytes per second.
 */
public final class TaskMapper {

    private TaskMapper() {}

    public static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    public static TaskDto toDto(DownloadTask t) {
        DownloadStatus status = t.getStatus();
        List<SubTaskDto> subs = t.getSubTasks().stream().map(TaskMapper::toDto).toList();
        double progress = status == DownloadStatus.COMPLETED ? 100.0 : nz(t.getAggregatedProgress());
        boolean running = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.MERGING
                || status == DownloadStatus.EXTRACTING || status == DownloadStatus.COPYING;
        Long downloaded = t.getAggregatedDownloadedBytes();
        return new TaskDto(
                t.getId(),
                t.getSource(),
                t.getContentType(),
                t.getDisplayName(),
                t.getTitle(),
                t.getEpisodeName(),
                t.getYear(),
                t.getSeason(),
                t.getEpisode(),
                t.getTmdbId(),
                t.getLanguages() != null ? List.copyOf(t.getLanguages()) : List.of(),
                t.getQuality(),
                t.getOutputPath(),
                status,
                progress,
                downloaded,
                t.getAggregatedTotalBytes(),
                running && status == DownloadStatus.DOWNLOADING
                        ? (t.getSpeedBps() != null ? t.getSpeedBps() : speed(downloaded, t.getStartedAt())) : null,
                running ? t.getAggregatedEtaSeconds() : null,
                t.getErrorMessage(),
                t.getStatus() == DownloadStatus.QUEUED || t.getStatus() == DownloadStatus.DOWNLOADING
                        ? t.getStatusMessage() : null,
                toInstant(t.getCreatedAt()),
                toInstant(t.getStartedAt()),
                toInstant(t.getCompletedAt()),
                DownloadQueueService.canCancel(status),
                DownloadQueueService.canRetry(status),
                subs
        );
    }

    public static SubTaskDto toDto(DownloadSubTask s) {
        boolean downloading = s.getStatus() == DownloadStatus.DOWNLOADING;
        return new SubTaskDto(
                s.getId(),
                s.getType(),
                s.getLanguage(),
                s.getDisplayName(),
                s.getResolution(),
                s.getStatus(),
                s.getStatus() == DownloadStatus.COMPLETED ? 100.0 : nz(s.getProgress()),
                s.getDownloadedBytes(),
                s.getTotalBytes(),
                downloading ? speed(s.getDownloadedBytes(), s.getStartedAt()) : null,
                downloading ? s.getEtaSeconds() : null,
                s.getErrorMessage(),
                toInstant(s.getStartedAt()),
                toInstant(s.getCompletedAt())
        );
    }

    /** Average speed since start, in bytes per second. */
    static Double speed(Long downloadedBytes, LocalDateTime startedAt) {
        if (downloadedBytes == null || downloadedBytes <= 0 || startedAt == null) {
            return null;
        }
        long millis = Duration.between(startedAt, LocalDateTime.now()).toMillis();
        if (millis < 1000) {
            return null;
        }
        return downloadedBytes * 1000.0 / millis;
    }

    private static double nz(Double d) {
        return d == null || d.isNaN() ? 0.0 : d;
    }
}
