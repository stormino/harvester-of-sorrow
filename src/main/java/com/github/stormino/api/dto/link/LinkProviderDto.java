package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A link download provider and whether it can start downloads now")
public record LinkProviderDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mega") String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Mega") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"READY", "IDLE", "WARMING_UP", "ERROR"})
        String state,
        @Schema(description = "Detail, e.g. warm-up progress") String message,
        Integer done,
        Integer total
) {}
