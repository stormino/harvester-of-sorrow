package com.github.stormino.api.dto.link;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Queue files of a resolved link, each with the identity the user confirmed")
public record LinkDownloadRequest(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mega") String provider,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @NotEmpty @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items
) {
    @Schema(name = "LinkDownloadItem", description = "One file and what it is")
    public record Item(
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "LinkFile.url") String fileUrl,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
            Long size,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
            @Schema(description = "Confirmed TMDB match; omit for a manual title") Integer tmdbId,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            Integer year,
            Integer season,
            Integer episode,
            @Schema(description = "Filled from TMDB when omitted and tmdbId is set") String episodeName
    ) {}
}
