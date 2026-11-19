package com.github.stormino.service.source;

import com.github.stormino.model.source.SourceMetadata;

import java.time.LocalDate;

/**
 * A single episode produced by {@link MediaSourceProvider#listEpisodes}, ready
 * to be turned into a {@link com.github.stormino.model.DownloadTask} without
 * further provider-specific lookups.
 *
 * @param season           1-based season number
 * @param episode          1-based episode number within the season
 * @param name             optional human-readable title (e.g. "Pista nera")
 * @param sourceMetadata   per-source descriptor pointing at the playable item
 * @param airDate          first air date, when the source knows it (VixSrc via TMDB)
 */
public record EpisodeRef(int season,
                         int episode,
                         String name,
                         SourceMetadata sourceMetadata,
                         LocalDate airDate) {

    public EpisodeRef(int season, int episode, String name, SourceMetadata sourceMetadata) {
        this(season, episode, name, sourceMetadata, null);
    }
}
