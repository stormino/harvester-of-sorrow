package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One search hit")
public record SearchResultDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Breaking Bad") String title,
        String originalTitle,
        @Schema(description = "Title in the configured download language, when it differs (TMDB)", example = "Curioso come George")
        String localTitle,
        Integer year,
        String overview,
        @Schema(description = "Rating 0-10", example = "8.9") Double rating,
        Integer tmdbId,
        @Schema(description = "Public page of the title (TMDB or RaiPlay)") String externalUrl,
        String posterUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SeasonDto> seasons,
        Integer totalEpisodes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Opaque source reference; pass back unchanged to /content/episodes and /downloads")
        String sourceRef
) {}
