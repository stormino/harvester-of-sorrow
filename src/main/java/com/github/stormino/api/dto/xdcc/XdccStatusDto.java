package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Whether XDCC search can run, per source")
public record XdccStatusDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "At least one source can search") boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccSourceDto> sources,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Sent to the search sources") String userAgent
) {}
