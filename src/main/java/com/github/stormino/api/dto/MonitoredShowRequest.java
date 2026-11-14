package com.github.stormino.api.dto;

import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Create or update a monitored show. `directoryName` is ignored on update.")
public record MonitoredShowRequest(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Breaking Bad") String title,
        Integer year,
        Integer tmdbId,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @Schema(description = "sourceRef from a search result") String sourceRef,
        @Schema(description = "Folder under DOWNLOAD_TV_SHOWS_PATH (required on create)", example = "Breaking.Bad.2008")
        String directoryName
) {}
