package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlaylistInfo {

    private String url;
    private String language;
    private boolean verified;

    /**
     * Referer URL to send when fetching this playlist (and its segments).
     * Source-specific: VixSrc needs the embed page URL, RaiPlay needs the
     * page URL it was discovered on.
     */
    private String referer;

    public static PlaylistInfo of(String url, String language) {
        return PlaylistInfo.builder()
                .url(url)
                .language(language)
                .verified(false)
                .build();
    }
}
