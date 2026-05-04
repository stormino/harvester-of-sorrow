package com.github.stormino.model.source;

import com.github.stormino.model.MediaSource;

public record RaiPlayMetadata(
        String pathId,
        String contentUuid,
        String programUuid,
        String seasonId,
        String episodeUuid
) implements SourceMetadata {

    @Override
    public MediaSource source() {
        return MediaSource.RAIPLAY;
    }
}
