package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "One track (video, audio or subtitle) of a download task")
public record SubTaskDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadSubTask.SubTaskType type,
        String language,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Video (1920x1080)") String displayName,
        String resolution,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0-100") double progress,
        Long downloadedBytes,
        Long totalBytes,
        @Schema(description = "Bytes per second") Double speedBps,
        Long etaSeconds,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {}
