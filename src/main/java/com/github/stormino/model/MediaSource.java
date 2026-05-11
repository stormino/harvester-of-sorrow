package com.github.stormino.model;

public enum MediaSource {
    VIXSRC("VixSrc"),
    RAIPLAY("RaiPlay");

    private final String displayName;

    MediaSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
