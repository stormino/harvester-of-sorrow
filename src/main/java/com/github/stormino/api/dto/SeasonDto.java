package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A season of a TV show as known at search time")
public record SeasonDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int number,
        @Schema(description = "Episode count, when known", example = "10") Integer episodeCount
) {}
