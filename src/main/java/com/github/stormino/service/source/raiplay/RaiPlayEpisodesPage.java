package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Response of {@code /programmi/{slug}/{publishingBlockId}/{contentSetId}/episodes.json}.
 *
 * <p>The naming in the API is a bit confusing:
 * <pre>
 * seasons[]                ← actually publishing blocks ("Episodi", "Extra", ...)
 *   .episodes[]            ← actually season ContentSets ("Stagione 1", ...)
 *     .cards[]             ← actual episode video items
 * </pre>
 *
 * <p>We ignore the "Extra" block and only read the {@code "Episodi"} entry.
 * Within it, {@code episodes[0]} is the requested season's ContentSet whose
 * {@code cards[]} are the episodes; each card has a {@code path_id} pointing
 * to a video descriptor and string {@code season} / {@code episode} numbers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlayEpisodesPage(
        String name,
        String id,
        List<SeasonBlock> seasons
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonBlock(
            String id,
            String label,
            String type,
            List<SeasonContentSet> episodes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonContentSet(
            String id,
            String label,
            String type,
            List<EpisodeCard> cards
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpisodeCard(
            String id,
            @JsonProperty("path_id") String pathId,
            String name,
            @JsonProperty("episode_title") String episodeTitle,
            String season,
            String episode,
            String duration
    ) {

        public Integer parseSeason() {
            return parseInt(season);
        }

        public Integer parseEpisode() {
            return parseInt(episode);
        }

        private static Integer parseInt(String s) {
            if (s == null || s.isBlank()) return null;
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
    }

    /**
     * Returns the season's ContentSet from the {@code "Episodi"} block.
     * {@code "Extra"} and similar non-episode blocks are ignored.
     */
    public Optional<SeasonContentSet> episodiSeason() {
        if (seasons == null) return Optional.empty();
        return seasons.stream()
                .filter(s -> "Episodi".equals(s.label()))
                .filter(s -> s.episodes() != null && !s.episodes().isEmpty())
                .map(s -> s.episodes().get(0))
                .findFirst();
    }
}
