package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "What a link points to, with a guess of what it is and TMDB candidates")
public record LinkResolutionDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mega") String provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Mega") String providerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean folder,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Files left out of a very large folder") int truncated,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LinkFileDto> files,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Guess for the link as a whole (folder name, or the single file)")
        LinkGuessDto guess,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "TMDB candidates for the guess, best first; empty when TMDB is not configured")
        List<TmdbMatchDto> matches,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean tmdbAvailable
) {}
