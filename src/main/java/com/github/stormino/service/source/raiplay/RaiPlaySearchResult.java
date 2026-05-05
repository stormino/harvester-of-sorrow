package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One search hit from the RaiPlay search API.
 * Field names are best-guess pending real fixture capture; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlaySearchResult(
        String id,
        String name,
        // "Film", "Film tv", "ProgrammaCompleto", "EpisodioSezione", etc.
        String type,
        String uid,
        @JsonProperty("path_id") String pathId,
        @JsonProperty("program_info") ProgramInfo programInfo,
        @JsonProperty("date_published") String datePublished,
        String description,
        Integer season,
        Integer episode
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramInfo(
            String uid,
            @JsonProperty("path_id") String pathId
    ) {}
}
