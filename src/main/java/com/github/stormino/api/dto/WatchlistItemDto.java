package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A title saved for later")
public record WatchlistItemDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Title key as the search page builds it",
                example = "MOVIE:976893") String titleKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        Integer tmdbId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Perfect Days") String title,
        Integer year,
        String overview,
        Double rating,
        String posterUrl,
        String externalUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Streaming search results of the title, as saved; pass each to the download form")
        List<SearchResultDto> streams,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt
) {}
