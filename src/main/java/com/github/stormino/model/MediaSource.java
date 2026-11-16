package com.github.stormino.model;

public enum MediaSource {
    VIXSRC("VixSrc"),
    RAIPLAY("RaiPlay"),
    /** Direct link download (Mega, plain HTTP, ...): see {@code service.link}. Not a searchable catalogue. */
    LINK("Link");

    private final String displayName;

    MediaSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
