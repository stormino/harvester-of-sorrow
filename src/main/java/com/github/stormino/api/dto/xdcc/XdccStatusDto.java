package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Whether XDCC search can run (the PW Search Engine pack database)")
public record XdccStatusDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Schema(description = "Why search is unavailable") String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int networks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int lists,
        Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Sent to the PW Search Engine and list pages")
        String userAgent
) {}
