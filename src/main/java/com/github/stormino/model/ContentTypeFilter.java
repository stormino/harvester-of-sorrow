package com.github.stormino.model;

public enum ContentTypeFilter {
    MOVIES,
    TV,
    BOTH;

    public boolean includesMovies() {
        return this == MOVIES || this == BOTH;
    }

    public boolean includesTv() {
        return this == TV || this == BOTH;
    }
}
