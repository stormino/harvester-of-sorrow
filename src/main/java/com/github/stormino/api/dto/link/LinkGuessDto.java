package com.github.stormino.api.dto.link;

import com.github.stormino.model.DownloadTask;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a file name looks like (a guess to confirm)")
public record LinkGuessDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "May be empty") String title,
        Integer year,
        Integer season,
        Integer episode,
        String episodeName
) {}
