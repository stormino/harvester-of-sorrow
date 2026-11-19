package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Read-only configuration (change via environment variables and restart)")
public record SettingsDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String moviesPath,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tvShowsPath,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tempPath,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int parallelDownloads,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int segmentConcurrency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String defaultQuality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String defaultLanguage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String extractorBaseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int extractorTimeoutSeconds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean monitoringEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the automatic check runs, in words",
                example = "daily at 03:00 and 06:00") String monitoringSchedule,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Days a new episode waits for audio in the default language")
        int monitoringLanguageWaitDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean tmdbApiKeySet,
        @Schema(description = "Masked TMDB key, e.g. `••••••••3f9a`; null when unset") String tmdbApiKeyMasked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean raiPlayEnabled
) {}
