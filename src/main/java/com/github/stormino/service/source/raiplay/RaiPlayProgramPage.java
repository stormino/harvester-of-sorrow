package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Response shape of {@code /programmi/{slug}.json}.
 *
 * <p>For movies, {@link #firstItemPath()} points directly at the video
 * descriptor (e.g. {@code /video/2018/12/COSMONAUTA-...json}) — no further
 * traversal is needed.
 *
 * <p>For TV shows, the real-episode block lives under {@link #blocks()} and is
 * identified by sets whose {@code type == "RaiPlay Multimedia Set"}. Sister
 * blocks like "Extra" use {@code "RaiPlay Content Set"} and must be ignored.
 * Each set in the episode block represents a season; per-season episodes live
 * at {@code /programmi/{slug}/{publishingBlockId}/{contentSetId}/episodes.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlayProgramPage(
        String name,
        String id,
        @JsonProperty("path_id") String pathId,
        @JsonProperty("first_item_path") String firstItemPath,
        @JsonProperty("first_item_duration") String firstItemDuration,
        List<Block> blocks,
        @JsonProperty("program_info") ProgramInfo programInfo
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String name, String id, String type, List<ContentSet> sets) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentSet(
            String name,
            String id,
            String type,
            @JsonProperty("path_id") String pathId,
            @JsonProperty("episode_size") EpisodeSize episodeSize
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpisodeSize(String label, Integer number) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramInfo(
            String typology,
            String layout,
            @JsonProperty("seasons_number") String seasonsNumber
    ) {}

    /** A movie page has {@code program_info.typology == "Film"}. */
    public boolean isMovie() {
        return programInfo != null && "Film".equals(programInfo.typology());
    }

    /**
     * The block that holds real episodes. Identified by the presence of at
     * least one set with {@code type == "RaiPlay Multimedia Set"} (the "Extra"
     * sibling uses {@code "RaiPlay Content Set"}).
     */
    public Optional<Block> episodesBlock() {
        if (blocks == null) return Optional.empty();
        return blocks.stream()
                .filter(b -> b.sets() != null
                        && b.sets().stream().anyMatch(s -> "RaiPlay Multimedia Set".equals(s.type())))
                .findFirst();
    }
}
