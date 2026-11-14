package com.github.stormino.api.dto;

import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = """
        Queue a movie, a whole show, whole seasons or specific episodes.
        TV: empty `seasons` = whole show; one or more `seasons` with empty `episodes` = those seasons in full;
        exactly one season plus `episodes` = those episodes.""")
public record DownloadRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MediaSource source,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DownloadTask.ContentType contentType,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "sourceRef from a search result") String sourceRef,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Breaking Bad") String title,
        Integer year,
        List<Integer> seasons,
        List<Integer> episodes,
        @Schema(description = "Audio languages; empty = server default", example = "[\"en\"]") List<String> languages,
        @Schema(description = "best, 1080, 720 or worst; empty = server default", example = "best") String quality
) {}
