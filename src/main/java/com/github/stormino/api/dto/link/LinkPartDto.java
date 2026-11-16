package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "One volume of a multi-part archive")
public record LinkPartDto(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        Long size
) {}
