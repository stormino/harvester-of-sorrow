package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Audio, subtitles and qualities a title really has (for a show: its first episode)")
public record MediaInfoDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether the source could describe the title; "
                + "false = unknown, offer the source's full language list") boolean known,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrackDto> audio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrackDto> subtitles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "best, available heights (highest first), worst",
                example = "[\"best\", \"720\", \"480\", \"worst\"]") List<String> qualities
) {
    public record TrackDto(
            @Schema(description = "Two-letter code; null when unknown", example = "it") String language,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Italian") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean forced
    ) {}
}
