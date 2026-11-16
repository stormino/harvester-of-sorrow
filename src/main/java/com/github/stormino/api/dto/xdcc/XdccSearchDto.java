package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "XDCC search results, merged from every source")
public record XdccSearchDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccPackDto> packs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccSourceDto> sources
) {}
