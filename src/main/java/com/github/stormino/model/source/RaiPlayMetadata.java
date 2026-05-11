package com.github.stormino.model.source;

import com.github.stormino.model.MediaSource;

public record RaiPlayMetadata(
        String pathId,
        String contentUuid,
        String programUuid,
        String seasonId,
        String episodeUuid
) implements SourceMetadata {

    private static final String BASE_URL = "https://www.raiplay.it";

    @Override
    public MediaSource source() {
        return MediaSource.RAIPLAY;
    }

    /**
     * Public RaiPlay page URL for this program (e.g.
     * {@code https://www.raiplay.it/programmi/roccoschiavone}).
     * Returns {@code null} when the metadata has no usable {@code pathId}.
     */
    public String webUrl() {
        if (pathId == null || pathId.isBlank()) return null;
        String p = pathId.endsWith(".json")
                ? pathId.substring(0, pathId.length() - 5)
                : pathId;
        return BASE_URL + p;
    }
}
