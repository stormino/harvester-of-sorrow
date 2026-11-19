package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Which title to describe; for a show, the first episode the source has is checked")
public record MediaInfoRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "sourceRef from a search result") String sourceRef
) {}
