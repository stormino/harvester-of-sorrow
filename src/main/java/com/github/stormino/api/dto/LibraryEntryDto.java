package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A show folder on disk, with its monitoring config if any")
public record LibraryEntryDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String directoryName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int seasonCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int episodeCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False when a monitored show's folder is gone")
        boolean directoryExists,
        @Schema(description = "Null when the folder is not monitored") MonitoredShowDto monitored
) {}
