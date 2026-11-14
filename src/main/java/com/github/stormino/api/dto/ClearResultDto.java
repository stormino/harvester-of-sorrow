package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClearResultDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Number of tasks removed") int removed
) {}
