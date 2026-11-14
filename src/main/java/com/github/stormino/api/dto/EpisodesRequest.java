package com.github.stormino.api.dto;

import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Which show (and optionally which season) to list episodes for")
public record EpisodesRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceRef,
        @Schema(description = "Show title (used for logging only)") String title,
        @Schema(description = "Limit to one season") Integer season
) {}
