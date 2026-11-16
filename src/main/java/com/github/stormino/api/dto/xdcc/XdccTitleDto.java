package com.github.stormino.api.dto.xdcc;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Releases of one title, best first")
public record XdccTitleDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        Integer year,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "3: starts with the search words, 2: contains them in order, 1: contains them all, 0: matched elsewhere")
        int relevance,
        Integer tmdbId,
        String tmdbTitle,
        String posterUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccReleaseDto> releases
) {}
