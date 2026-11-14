package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Disk usage of a configured download path")
public record DiskDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "movies") String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String path,
        Long freeBytes,
        Long totalBytes
) {}
