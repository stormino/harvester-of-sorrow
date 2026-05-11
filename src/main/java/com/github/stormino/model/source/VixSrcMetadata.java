package com.github.stormino.model.source;

import com.github.stormino.model.MediaSource;

public record VixSrcMetadata(int tmdbId, Integer season, Integer episode) implements SourceMetadata {

    @Override
    public MediaSource source() {
        return MediaSource.VIXSRC;
    }
}
