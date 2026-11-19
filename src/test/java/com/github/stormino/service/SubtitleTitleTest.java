package com.github.stormino.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtitleTitleTest {

    static HlsParserService.SubtitleTrack track(String lang, String name, boolean forced) {
        return HlsParserService.SubtitleTrack.builder().language(lang).name(name).forced(forced).build();
    }

    @Test
    void namesForcedTracksConsistently() {
        assertEquals("Italian", TrackDownloadOrchestrator.subtitleTitle(track("ita", "Italian", false)));
        assertEquals("Italian (Forced)", TrackDownloadOrchestrator.subtitleTitle(track("ita", "Italian [Forced]", true)));
        assertEquals("English (Forced)", TrackDownloadOrchestrator.subtitleTitle(track("eng", "English", true)));
        assertEquals("Italian", TrackDownloadOrchestrator.subtitleTitle(track("ita", null, false)));
    }

    @Test
    void matchesRequestedLanguagesWhateverTheCode() {
        var langs = java.util.List.of("es", "de", "ja", "it");
        org.junit.jupiter.api.Assertions.assertTrue(TrackDownloadOrchestrator.languageMatchesAny("spa", langs));
        org.junit.jupiter.api.Assertions.assertTrue(TrackDownloadOrchestrator.languageMatchesAny("ger", langs));
        org.junit.jupiter.api.Assertions.assertTrue(TrackDownloadOrchestrator.languageMatchesAny("jpn", langs));
        org.junit.jupiter.api.Assertions.assertTrue(TrackDownloadOrchestrator.languageMatchesAny("ita-forced", langs));
        org.junit.jupiter.api.Assertions.assertTrue(TrackDownloadOrchestrator.languageMatchesAny("forced-ita", langs));
        org.junit.jupiter.api.Assertions.assertFalse(TrackDownloadOrchestrator.languageMatchesAny("eng", langs));
        org.junit.jupiter.api.Assertions.assertFalse(TrackDownloadOrchestrator.languageMatchesAny("est", langs)); // not "es"
        org.junit.jupiter.api.Assertions.assertFalse(TrackDownloadOrchestrator.languageMatchesAny(null, langs));
    }
}
