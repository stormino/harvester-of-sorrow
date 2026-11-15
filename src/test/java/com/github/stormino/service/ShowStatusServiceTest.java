package com.github.stormino.service;

import com.github.stormino.model.MonitoredShow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowStatusServiceTest {

    private TmdbMetadataService tmdb;
    private MutableClock clock;
    private ShowStatusService service;

    @BeforeEach
    void setUp() {
        tmdb = mock(TmdbMetadataService.class);
        when(tmdb.isAvailable()).thenReturn(true);
        clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
        service = new ShowStatusService(tmdb, clock);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "For.All.Mankind.2019                  | For All Mankind                   | 2019",
            "How.to.sell.drugs.online.(fast).2019  | How to sell drugs online (fast)   | 2019",
            "And.Just.Like.That….2021              | And Just Like That…               | 2021",
            "Il.Commissario.Montalbano.1999        | Il Commissario Montalbano         | 1999",
            "Breaking Bad (2008)                   | Breaking Bad                      | 2008",
            "Dark_Matter_[2024]                    | Dark Matter                       | 2024",
            "1923.2022                             | 1923                              | 2022",
            "Shogun                                | Shogun                            | ",
            "2001                                  | 2001                              | ",
    })
    void parsesFolderNames(String folder, String title, Integer year) {
        ShowStatusService.FolderName parsed = ShowStatusService.parseFolderName(folder);
        assertEquals(title, parsed.title());
        assertEquals(year, parsed.year());
    }

    @Test
    void endedAndCanceledAreCompleted_othersAreNot() {
        assertTrue(new ShowStatusService.ShowStatus("Ended", 1).completed());
        assertTrue(new ShowStatusService.ShowStatus("Canceled", 1).completed());
        assertFalse(new ShowStatusService.ShowStatus("Returning Series", 1).completed());
        assertFalse(new ShowStatusService.ShowStatus("In Production", 1).completed());
        assertFalse(ShowStatusService.ShowStatus.UNKNOWN.completed());
    }

    @Test
    void monitoredShowUsesItsOwnTmdbId_noFolderSearch() {
        when(tmdb.getTvShowStatus(42)).thenReturn(Optional.of("Ended"));

        ShowStatusService.ShowStatus st = service.statusFor("Whatever.2010", monitored(42));

        assertTrue(st.completed());
        assertEquals(42, st.tmdbId());
        verify(tmdb, never()).findTvShowId(any(), any());
    }

    @Test
    void plainFolderIsMatchedByTitleAndYear() {
        when(tmdb.findTvShowId("Chernobyl", 2019)).thenReturn(Optional.of(87108));
        when(tmdb.getTvShowStatus(87108)).thenReturn(Optional.of("Ended"));

        ShowStatusService.ShowStatus st = service.statusFor("Chernobyl.2019", null);

        assertEquals("Ended", st.status());
        assertTrue(st.completed());
    }

    @Test
    void retriesWithoutYearWhenYearFilteredSearchFindsNothing() {
        when(tmdb.findTvShowId("Boris", 2007)).thenReturn(Optional.empty());
        when(tmdb.findTvShowId("Boris", null)).thenReturn(Optional.of(7));
        when(tmdb.getTvShowStatus(7)).thenReturn(Optional.of("Returning Series"));

        ShowStatusService.ShowStatus st = service.statusFor("Boris.2007", null);

        assertEquals(7, st.tmdbId());
        assertFalse(st.completed());
    }

    @Test
    void noMatchIsUnknownAndNotCompleted() {
        when(tmdb.findTvShowId(any(), any())).thenReturn(Optional.empty());

        ShowStatusService.ShowStatus st = service.statusFor("Home.Videos.2015", null);

        assertNull(st.status());
        assertFalse(st.completed());
        verify(tmdb, never()).getTvShowStatus(anyInt());
    }

    @Test
    void lookupsAreCachedUntilExpiry() {
        when(tmdb.findTvShowId("Lost", 2004)).thenReturn(Optional.of(4607));
        when(tmdb.getTvShowStatus(4607)).thenReturn(Optional.of("Ended"));

        service.statusFor("Lost.2004", null);
        service.statusFor("Lost.2004", null);
        verify(tmdb, times(1)).findTvShowId("Lost", 2004);
        verify(tmdb, times(1)).getTvShowStatus(4607);

        clock.advance(Duration.ofHours(13)); // status expires, folder match does not
        service.statusFor("Lost.2004", null);
        verify(tmdb, times(1)).findTvShowId("Lost", 2004);
        verify(tmdb, times(2)).getTvShowStatus(4607);
    }

    @Test
    void tmdbUnavailable_everythingUnknown() {
        when(tmdb.isAvailable()).thenReturn(false);

        assertNull(service.statusFor("Lost.2004", null).status());
        assertFalse(service.isCompleted(monitored(4607)));
        assertTrue(service.statusesFor(List.of(entry("Lost.2004", null))).isEmpty());
        verify(tmdb, never()).getTvShowStatus(anyInt());
    }

    @Test
    void schedulerRule_onlyTrustsTheShowsOwnTmdbId() {
        when(tmdb.getTvShowStatus(42)).thenReturn(Optional.of("Canceled"));
        assertTrue(service.isCompleted(monitored(42)));

        // A monitored show without a TMDB id (e.g. RaiPlay) is never skipped,
        // even if its folder name would match an ended show.
        MonitoredShow raiplay = MonitoredShow.builder().directoryName("Lost.2004").build();
        assertFalse(service.isCompleted(raiplay));
        verify(tmdb, never()).findTvShowId(any(), any());
    }

    @Test
    void batchLookupKeysByDirectory() {
        when(tmdb.findTvShowId(eq("Devs"), eq(2020))).thenReturn(Optional.of(1));
        when(tmdb.findTvShowId(eq("Foundation"), eq(2021))).thenReturn(Optional.of(2));
        when(tmdb.findTvShowId(any(), isNull())).thenReturn(Optional.empty());
        when(tmdb.getTvShowStatus(1)).thenReturn(Optional.of("Ended"));
        when(tmdb.getTvShowStatus(2)).thenReturn(Optional.of("Returning Series"));

        Map<String, ShowStatusService.ShowStatus> out = service.statusesFor(List.of(
                entry("Devs.2020", null), entry("Foundation.2021", null)));

        assertTrue(out.get("Devs.2020").completed());
        assertFalse(out.get("Foundation.2021").completed());
    }

    private static MonitoredShow monitored(int tmdbId) {
        return MonitoredShow.builder().tmdbId(tmdbId).directoryName("x").enabled(true).build();
    }

    private static MonitoringService.LibraryEntry entry(String dir, MonitoredShow m) {
        return new MonitoringService.LibraryEntry(dir, 1, 1, m);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
