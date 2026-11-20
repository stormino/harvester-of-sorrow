package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A title's trailer")
public record TrailerDto(
        @Schema(description = "YouTube link; null when TMDB has no trailer",
                example = "https://www.youtube.com/watch?v=dQw4w9WgXcQ") String url,
        @Schema(description = "Language of the trailer, two-letter code", example = "it") String language
) {}
