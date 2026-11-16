package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Quality tags read from a release name")
public record XdccQualityDto(
        @Schema(description = "Vertical resolution", example = "1080") Integer resolution,
        @Schema(example = "WEB-DL") String source,
        @Schema(example = "x265") String codec,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hdr,
        @Schema(example = "AC3") String audio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"ITA\"]") List<String> languages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"ITA\"]") List<String> subtitles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "CAM/TS or line-dubbed") boolean lowQuality
) {}
