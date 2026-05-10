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

    @Test
    void parsesProgramInfo_movieHasGenreNoSeasons() throws Exception {
        RaiPlayProgramInfo info = readFixture(
                "raiplay/program-info-cosmonauta.json", RaiPlayProgramInfo.class);

        assertEquals("Cosmonauta", info.name());
        assertEquals("/programmi/cosmonauta.json", info.pathId());
        assertFalse(info.isTvShow(), "Cosmonauta has no 'seasons' detail → movie");
        assertTrue(info.seasonCount().isEmpty());
    }

    @Test
    void parsesProgramInfo_tvShowAdvertisesSeasonCount() throws Exception {
        RaiPlayProgramInfo info = readFixture(
                "raiplay/program-info-schiavone.json", RaiPlayProgramInfo.class);

        assertEquals("Rocco Schiavone", info.name());
        assertEquals("/programmi/roccoschiavone.json", info.pathId());
        assertTrue(info.isTvShow());
        assertEquals(6, info.seasonCount().orElse(-1));
    }

    @Test
    void parsesProgramPage_movieResolvesFirstItemPath() throws Exception {
        RaiPlayProgramPage page = readFixture(
                "raiplay/program-cosmonauta.json", RaiPlayProgramPage.class);

        assertTrue(page.isMovie());
        assertEquals("Film", page.programInfo().typology());
        assertEquals("single", page.programInfo().layout());
        assertEquals(
                "/video/2018/12/COSMONAUTA-f5cbe4fd-7eb2-490f-af5b-ff0cd2009973.json",
                page.firstItemPath());
        // movies still expose a blocks structure but with a single non-Multimedia set
        assertNotNull(page.blocks());
        assertEquals(1, page.blocks().size());
    }

    @Test
    void parsesProgramPage_tvShowExposesEpisodesBlock() throws Exception {
        RaiPlayProgramPage page = readFixture(
                "raiplay/program-schiavone.json", RaiPlayProgramPage.class);

        assertFalse(page.isMovie());
        assertEquals("Fiction", page.programInfo().typology());
        assertEquals("multi", page.programInfo().layout());
        assertEquals("6", page.programInfo().seasonsNumber());

        RaiPlayProgramPage.Block episodi = page.episodesBlock().orElseThrow();
        assertEquals("Episodi", episodi.name());
        assertEquals("PublishingBlock-43a4d6c4-719c-440c-9a70-265371014bb5", episodi.id());
        assertEquals(6, episodi.sets().size());

        RaiPlayProgramPage.ContentSet s1 = episodi.sets().get(0);
        assertEquals("Stagione 1", s1.name());
        assertEquals("ContentSet-e248fdd9-215f-499d-adf8-fe0aba70fbaf", s1.id());
        assertEquals("RaiPlay Multimedia Set", s1.type());
        assertEquals(6, s1.episodeSize().number());
    }

    @Test
    void parsesEpisodesPage_filtersExtraBlock() throws Exception {
        RaiPlayEpisodesPage page = readFixture(
                "raiplay/episode-schiavone-s1.json", RaiPlayEpisodesPage.class);

        assertEquals("Rocco Schiavone", page.name());
        assertEquals(2, page.seasons().size(),
                "response includes both Episodi and Extra blocks");

        RaiPlayEpisodesPage.SeasonContentSet s1 = page.episodiSeason().orElseThrow();
        assertEquals("Stagione 1", s1.label());
        assertEquals(6, s1.cards().size());

        RaiPlayEpisodesPage.EpisodeCard e1 = s1.cards().get(0);
        assertEquals(1, e1.parseSeason());
        assertEquals(1, e1.parseEpisode());
        assertEquals("Pista nera", e1.episodeTitle());
        assertEquals("Rocco Schiavone - S1E1 - Pista nera", e1.name());
        assertEquals(
                "/video/2016/11/Rocco-Schiavone-S1E1-Pista-nera-7f69fae5-8252-4169-bb65-c5b8994000c9.json",
                e1.pathId());

        // Last episode in this season
        RaiPlayEpisodesPage.EpisodeCard e6 = s1.cards().get(5);
        assertEquals(1, e6.parseSeason());
        assertEquals(6, e6.parseEpisode());
        assertEquals("Pulizie di primavera", e6.episodeTitle());
    }

    private <T> T readFixture(String resourcePath, Class<T> type) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "fixture not found on test classpath: " + resourcePath);
            return mapper.readValue(in, type);
        }
    }
}
