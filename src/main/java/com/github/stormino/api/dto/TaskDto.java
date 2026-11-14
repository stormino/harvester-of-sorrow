package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "A download task with its tracks")
public record TaskDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Breaking Bad S01E01 - Pilot") String displayName,
        String title,
        String episodeName,
        Integer year,
        Integer season,
        Integer episode,
        Integer tmdbId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> languages,
        String quality,
        String outputPath,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0-100") double progress,
        Long downloadedBytes,
        Long totalBytes,
        @Schema(description = "Bytes per second") Double speedBps,
        Long etaSeconds,
        String errorMessage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether POST /cancel is allowed") boolean canCancel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether POST /retry is allowed") boolean canRetry,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SubTaskDto> subTasks
) {}
