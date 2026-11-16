package com.github.stormino.api.dto.link;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A TMDB title a link may be")
public record TmdbMatchDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int tmdbId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        String originalTitle,
        Integer year,
        String overview,
        String posterUrl
) {}
