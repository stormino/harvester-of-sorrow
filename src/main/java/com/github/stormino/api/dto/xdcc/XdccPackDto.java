package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One XDCC pack found by a search")
public record XdccPackDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String network,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String channel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String bot,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pack,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "As listed (e.g. 1.4G)") String size,
        @Schema(description = "Approximate bytes") Long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "xdcc:// link; pass to POST /links/resolve-batch")
        String url
) {}
