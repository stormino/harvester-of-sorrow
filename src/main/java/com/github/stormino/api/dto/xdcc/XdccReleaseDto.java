package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One file (or multi-part archive) and every bot offering it")
public record XdccReleaseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "File name, or archive set name") String label,
        @Schema(example = "S01E02") String episode,
        String size,
        Long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) XdccQualityDto quality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mkv") String format,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bots offering it (tried in turn)") int bots,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> networks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Links to pass to POST /links/resolve-batch: one, or one per archive volume")
        List<String> urls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False when archive volumes are missing")
        boolean complete,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Integer> missingParts
) {}
