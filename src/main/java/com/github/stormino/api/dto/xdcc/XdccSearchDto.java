package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "XDCC search results: titles, each with its releases (bots merged)")
public record XdccSearchDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccTitleDto> titles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Raw hits before merging") int packs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<XdccSourceDto> sources
) {}
