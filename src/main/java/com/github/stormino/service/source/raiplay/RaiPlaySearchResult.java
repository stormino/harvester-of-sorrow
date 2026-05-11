package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single video card from {@code agg.video.cards[]} in the RaiPlay search response.
 * Represents one specific film or TV episode (as opposed to a program/series root).
 *
 * <p>For films: {@code stagione} and {@code episodio} are blank/empty.
 * For TV episodes: both are non-blank string numbers (e.g. "1", "12").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlaySearchResult(
        String id,
        String titolo,
        String programma,
        String stagione,
        String episodio,
        String sommario,
        @JsonProperty("aria_label") String ariaLabel,
        @JsonProperty("path_id") String pathId,
        @JsonProperty("parent_path_id") String parentPathId,
        @JsonProperty("info_url") String infoUrl,
        @JsonProperty("duration_in_minutes") String durationInMinutes,
        String durata
) {
    public boolean isEpisode() {
        return notBlank(stagione) && notBlank(episodio);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
