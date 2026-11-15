package com.github.stormino.util;

import com.github.stormino.util.ShowDirectoryScanner.EpisodeKey;
import com.github.stormino.util.ShowDirectoryScanner.Scan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowDirectoryScannerTest {

    @TempDir
    Path show;

    @Test
    void appLayout_seasonFoldersWithMp4() throws IOException {
        touch("Season 01/Severance.S01E01.mp4");
        touch("Season 01/Severance.S01E02.mp4");
        touch("Season 02/Severance.S02E01.mp4");

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(2, scan.seasonCount());
        assertEquals(3, scan.episodeCount());
        assertEquals(new EpisodeKey(2, 1), scan.latest().orElseThrow());
    }

    @Test
    void looseEpisodesInShowFolderPlusSeasonFolderOfMkv() throws IOException {
        // For.All.Mankind.2019: S01–S04 loose in the show folder, S05 in a season folder as .mkv
        for (int s = 1; s <= 4; s++) {
            for (int e = 1; e <= 10; e++) {
                touch(String.format("For.All.Mankind.S%02dE%02d.Title.mp4", s, e));
            }
        }
        touch("Season 05/For All Mankind - S05E01 - First Light WEBDL-1080p.mkv");
        touch("Season 05/For All Mankind - S05E02 - The Hard Six WEBDL-1080p.mkv");

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(5, scan.seasonCount());
        assertEquals(42, scan.episodeCount());
        assertEquals(new EpisodeKey(5, 2), scan.latest().orElseThrow());
        assertTrue(scan.contains(3, 7));
        assertTrue(scan.contains(5, 1));
        assertFalse(scan.contains(5, 3));
    }

    @Test
    void mkvWithSubtitles_subtitlesIgnored() throws IOException {
        // Chernobyl.2019 layout
        for (int e = 1; e <= 5; e++) {
            touch(String.format("Season 01/Chernobyl.S01E%02d.1080p.AMZN.WEB-DL.x265-HETeam.mkv", e));
            touch(String.format("Season 01/Chernobyl.S01E%02d.1080p.AMZN.WEB-DL.x265-HETeam.ita.srt", e));
            touch(String.format("Season 01/Chernobyl.S01E%02d.1080p.AMZN.WEB-DL.x265-HETeam.eng.sdh.srt", e));
        }

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(1, scan.seasonCount());
        assertEquals(5, scan.episodeCount());
    }

    @Test
    void sameEpisodeInTwoFormatsCountsOnce() throws IOException {
        touch("Season 01/Show.S01E01.mp4");
        touch("Season 01/Show - S01E01 - Pilot.mkv");
        touch("Show.S01E01.avi");

        assertEquals(1, ShowDirectoryScanner.scan(show).episodeCount());
    }

    @Test
    void hiddenFilesAndNonVideoAreIgnored() throws IOException {
        touch("Season 01/._Show.S01E05.mp4"); // macOS resource fork on NAS shares
        touch("Season 01/.Show.S01E06.mkv");
        touch("Season 01/Show.S01E07.nfo");
        touch("Season 01/Show.S01E08.mp4.part");
        touch("Season 01/Show.S01E01.mp4");

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(1, scan.episodeCount());
        assertEquals(new EpisodeKey(1, 1), scan.latest().orElseThrow());
    }

    @Test
    void unnumberedFilesCountOnlyInsideSeasonFolders() throws IOException {
        touch("Season 02/Episode one.mp4");
        touch("Season 02/Episode two.mkv");
        touch("Extras/Behind the scenes.mp4");
        touch("Trailer.mp4");

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(1, scan.seasonCount());
        assertEquals(2, scan.episodeCount());
        assertTrue(scan.latest().isEmpty(), "unnumbered files give no anchor for monitoring");
    }

    @Test
    void specialsCountAsEpisodesButNotAsSeasonOrLatest() throws IOException {
        touch("Specials/Show.S00E01.Christmas.mkv");
        touch("Season 01/Show.S01E03.mkv");

        Scan scan = ShowDirectoryScanner.scan(show);

        assertEquals(1, scan.seasonCount());
        assertEquals(2, scan.episodeCount());
        assertEquals(new EpisodeKey(1, 3), scan.latest().orElseThrow());
    }

    @Test
    void emptySeasonFolderIsNotASeason() throws IOException {
        Files.createDirectories(show.resolve("Season 03"));
        touch("Season 01/Show.S01E01.mp4");

        assertEquals(1, ShowDirectoryScanner.scan(show).seasonCount());
    }

    @Test
    void missingDirectoryIsEmpty() {
        assertSame(Scan.EMPTY, ShowDirectoryScanner.scan(show.resolve("nope")));
    }

    @ParameterizedTest
    @CsvSource({
            "Show.S01E02.mp4, 1, 2",
            "show.s1e2.mkv, 1, 2",
            "Show S01.E02 Title.mkv, 1, 2",
            "Show - S05E01 - First Light WEBDL-1080p.mkv, 5, 1",
            "Il.Commissario.Montalbano.S15E04.mp4, 15, 4",
            "One.Piece.S01E1071.mkv, 1, 1071",
    })
    void parsesEpisodeNumbers(String name, int season, int episode) {
        assertEquals(Optional.of(new EpisodeKey(season, episode)), ShowDirectoryScanner.parseEpisode(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Movie.1080p.x265.mkv", "BOSS01E02.mkv", "Trailer.mp4"})
    void noEpisodeNumber(String name) {
        assertTrue(ShowDirectoryScanner.parseEpisode(name).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"Season 01, 1", "season.2, 2", "S03, 3", "Stagione 4, 4", "Season_10, 10"})
    void recognisesSeasonFolders(String dir, int season) {
        assertEquals(season, ShowDirectoryScanner.seasonOfDir(dir));
    }

    @Test
    void otherFoldersAreNotSeasons() {
        assertNull(ShowDirectoryScanner.seasonOfDir("Extras"));
        assertNull(ShowDirectoryScanner.seasonOfDir("Specials"));
    }

    private void touch(String relative) throws IOException {
        Path p = show.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.createFile(p);
    }
}
