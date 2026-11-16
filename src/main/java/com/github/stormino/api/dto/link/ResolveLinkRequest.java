package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A link to inspect")
public record ResolveLinkRequest(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "https://mega.nz/file/XXXX#YYYY") String url,
        @Schema(description = "Provider id to use instead of detecting it from the URL", example = "mega") String provider
) {}
