package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Save a title for later. Saving one that is already there refreshes its details "
        + "(fields left empty keep their saved value) and keeps the date it was saved.")
public record WatchlistRequest(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "MOVIE:976893") String titleKey,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        Integer tmdbId,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Perfect Days") String title,
        Integer year,
        String overview,
        Double rating,
        String posterUrl,
        String externalUrl,
        @Schema(description = "Streaming search results of the title (may be empty for XDCC-only titles)")
        List<SearchResultDto> streams,
        @Schema(description = "Release date (films) or first air date (shows)") LocalDate releaseDate,
        @Schema(description = "Other names of the title (original, Italian) the daily check looks it up by")
        List<String> names,
        @Schema(description = "Whether a source serves the title now. False saves it as waiting: HoS checks every "
                + "day and flags it when it can be downloaded. Missing means true.")
        Boolean available
) {}
