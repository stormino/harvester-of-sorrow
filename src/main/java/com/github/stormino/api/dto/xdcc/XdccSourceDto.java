package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One XDCC search source and how it did")
public record XdccSourceDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "xdcc.eu") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Schema(description = "Detail: why it is unavailable, or what it found") String message
) {}
