package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Enabled sources and download defaults")
public record SourcesDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceDto> sources,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "en") String defaultLanguage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "best") String defaultQuality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> qualities
) {}
