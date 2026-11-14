package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of probing an external tool")
public record ToolCheckDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "ffmpeg") String tool,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Schema(description = "First line of `-version` output") String version,
        String error
) {}
