package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An episode that is really available on the source")
public record EpisodeDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int season,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int episode,
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceRef
) {}
