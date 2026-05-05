package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RaiPlay DTO parsing against real captured fixtures.
 * If RaiPlay's API shape drifts these tests should fail loudly.
 */
class RaiPlayDtoParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesFilmDescriptor() throws Exception {
        RaiPlayContentDescriptor d = readFixture("raiplay/film-cosmonauta.json", RaiPlayContentDescriptor.class);

        assertEquals("Cosmonauta", d.name());
        assertEquals("/video/2018/12/COSMONAUTA-f5cbe4fd-7eb2-490f-af5b-ff0cd2009973.json", d.pathId());
        assertTrue(d.isFilm());
        assertTrue(d.requiresLogin());
        assertNull(d.parseSeason());
        assertNull(d.parseEpisode());
        assertEquals(2009, d.extractYear());
        assertNotNull(d.video());
        assertEquals(
                "https://mediapolisvod.rai.it/relinker/relinkerServlet.htm?cont=AIKwyWsZEo8ib8F8bJU3KQeeqqEEqualeeqqEEqual",
                d.video().contentUrl());
        assertEquals("Film", d.programInfo().typology());
    }

    @Test
    void parsesEpisodeDescriptor() throws Exception {
        RaiPlayContentDescriptor d = readFixture(
                "raiplay/episode-rocco-s1e1.json", RaiPlayContentDescriptor.class);

        assertEquals("Rocco Schiavone - S1E1 - Pista nera", d.name());
        assertFalse(d.isFilm());
        assertTrue(d.requiresLogin());
        assertEquals(1, d.parseSeason());
        assertEquals(1, d.parseEpisode());
        assertEquals(2016, d.extractYear());
        assertEquals(
                "https://mediapolisvod.rai.it/relinker/relinkerServlet.htm?cont=WJvG7xrE5JoeeqqEEqual",
                d.video().contentUrl());
        // Italian SRT subtitles published alongside the video
        assertNotNull(d.video().subtitles());
        assertEquals(1, d.video().subtitles().size());
        assertEquals("it", d.video().subtitles().get(0).language());
        assertEquals("Fiction", d.programInfo().typology());
    }

    @Test
    void parsesSearchResponse() throws Exception {
        RaiPlaySearchResponse resp = readFixture(
                "raiplay/search-schiavone.json", RaiPlaySearchResponse.class);

        assertNotNull(resp.agg());
        assertNotNull(resp.agg().video());
        assertEquals(48, resp.agg().video().cards().size());

        RaiPlaySearchResult first = resp.agg().video().cards().get(0);
        assertNotNull(first.pathId());
        assertEquals("Rocco Schiavone", first.programma());
        assertTrue(first.isEpisode());
        assertNotNull(first.stagione());
        assertNotNull(first.episodio());

        // titoli bucket carries program-level cards
        assertNotNull(resp.agg().titoli());
        assertEquals(3, resp.agg().titoli().cards().size());
        RaiPlaySearchResponse.ProgramCard prog = resp.agg().titoli().cards().get(0);
        assertEquals("Programma", prog.tipo());
        assertTrue(prog.pathId().startsWith("/programmi/"));
    }

    private <T> T readFixture(String resourcePath, Class<T> type) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "fixture not found on test classpath: " + resourcePath);
            return mapper.readValue(in, type);
        }
    }
}
