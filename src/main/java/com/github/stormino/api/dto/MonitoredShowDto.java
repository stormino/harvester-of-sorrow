package com.github.stormino.api.dto;

import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A show directory linked to a source for automatic new-episode downloads")
public record MonitoredShowDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        Integer year,
        Integer tmdbId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        String sourceRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String directoryName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        Instant lastCheckedAt,
        Instant lastNewEpisodeAt,
        Instant createdAt
) {}
