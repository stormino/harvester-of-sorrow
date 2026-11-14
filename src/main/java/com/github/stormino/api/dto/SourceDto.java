package com.github.stormino.api.dto;

import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "An enabled media source")
public record SourceDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "VixSrc") String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Audio languages the source serves; empty = no restriction")
        List<String> languages
) {}
