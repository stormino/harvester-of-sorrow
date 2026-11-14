package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CheckResultDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "3") int newEpisodesEnqueued
) {}
