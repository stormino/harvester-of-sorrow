package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Content descriptor JSON from https://www.raiplay.it/{pathId}.json
 * Field names are best-guess pending real fixture capture; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlayContentDescriptor(
        String name,
        // Relinker URL — append &output=64 for HLS delivery
        @JsonProperty("content_url") String contentUrl,
        @JsonProperty("date_published") String datePublished,
        String description,
        Integer season,
        @JsonProperty("episode_number") Integer episodeNumber,
        @JsonProperty("program_info") ProgramInfo programInfo
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramInfo(
            String uid,
            @JsonProperty("path_id") String pathId,
            String name
    ) {}

    public Integer extractYear() {
        if (datePublished == null || datePublished.length() < 4) return null;
        try {
            return Integer.parseInt(datePublished.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean isFilm() {
        return season == null && episodeNumber == null;
    }
}
