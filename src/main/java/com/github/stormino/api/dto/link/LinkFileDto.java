package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One downloadable file of a link")
public record LinkFileDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Pass back unchanged when queueing") String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        Long size,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Folders above the file inside a shared folder")
        List<String> folders,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean video,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LinkGuessDto guess
) {}
