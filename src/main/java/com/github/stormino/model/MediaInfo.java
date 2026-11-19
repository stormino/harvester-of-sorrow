package com.github.stormino.model;

import java.util.List;

/**
 * What a title (or one episode) really offers on its source, read from its master
 * playlist before downloading anything.
 *
 * @param audio     audio tracks; one entry for audio built into the video
 * @param subtitles subtitle tracks
 * @param heights   video heights, highest first (e.g. 720, 480)
 */
public record MediaInfo(List<Track> audio, List<Track> subtitles, List<Integer> heights) {

    /**
     * @param language ISO 639-1 code ("it"), or null when unknown
     * @param name     what players show ("Italian", "Italian (Forced)")
     * @param forced   subtitles that only translate foreign-language lines and on-screen text
     */
    public record Track(String language, String name, boolean forced) {}

    public boolean hasAudio(String language) {
        return audio.stream().anyMatch(t -> com.github.stormino.util.LanguageCodes.same(t.language(), language));
    }
}
