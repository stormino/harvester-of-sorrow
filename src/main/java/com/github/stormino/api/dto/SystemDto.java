package com.github.stormino.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Runtime information")
public record SystemDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "5.0.0") String appVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String javaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String os,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int cpuCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DiskDto> disks
) {}
