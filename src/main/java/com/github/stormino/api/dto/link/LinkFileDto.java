package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One downloadable file of a link (a multi-part archive counts as one, with its parts)")
public record LinkFileDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Pass back unchanged when queueing") String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        @Schema(description = "Bytes; all parts together for multi-part archives") Long size,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Folders above the file inside a shared folder")
        List<String> folders,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean video,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unpacked after download") boolean archive,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Volumes of a multi-part archive (first one included); empty otherwise; pass back when queueing")
        List<LinkPartDto> parts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LinkGuessDto guess
) {}
