package com.github.stormino.service.link;

import com.github.stormino.model.DownloadTask.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReleaseNameParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv|The Matrix|1999",
            "Blade Runner 2049 (2017) [2160p].mkv|Blade Runner 2049|2017",
            "Il.Grande.Lebowski.1998.iTA-ENG.Bluray.1080p.x264-CYBER.mkv|Il Grande Lebowski|1998",
            "[YTS] Inception (2010) 720p.mp4|Inception|2010",
            "Amelie_2001_DVDRip_XviD.avi|Amelie|2001",
            "1917.2019.1080p.WEB-DL.mkv|1917|2019",
            "Some Movie.mkv|Some Movie|",
            "Dune.Part.Two.2024.HDR.2160p.WEB.H265-NoGroup.mkv|Dune Part Two|2024",
            "BigBuckBunny_320x180.mp4.zip|Big Buck Bunny|",
            "Sintel.2010.720p.mkv.part1.rar|Sintel|2010",
            "iCarly.mkv|iCarly|",
            "McQueen.2018.mkv|McQueen|2018",
    })
    void movies(String file, String title, Integer year) {
        var g = ReleaseNameParser.parse(file, List.of());
        assertEquals(ContentType.MOVIE, g.type());
        assertEquals(title, g.title());
        assertEquals(year, g.year());
        assertNull(g.season());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Breaking.Bad.S01E02.Cats.in.the.Bag.720p.WEB.mkv|Breaking Bad||1|2|Cats in the Bag",
            "the.office.us.s05e14.hdtv.x264.mkv|the office us||5|14|",
            "Doctor Who (2005) - 3x07 - 42.mkv|Doctor Who|2005|3|7|42",
            "Gomorra.La.Serie.S02E01E02.ITA.1080p.mkv|Gomorra La Serie||2|1|",
            "Don Matteo Stagione 13 Episodio 4.mp4|Don Matteo||13|4|",
            "Fargo.2014.S03E10.1080p.mkv|Fargo|2014|3|10|",
    })
    void episodes(String file, String title, Integer year, int season, int episode, String episodeTitle) {
        var g = ReleaseNameParser.parse(file, List.of());
        assertEquals(ContentType.TV, g.type());
        assertEquals(title, g.title());
        assertEquals(year, g.year());
        assertEquals(season, g.season());
        assertEquals(episode, g.episode());
        assertEquals(episodeTitle, g.episodeTitle());
    }

    @Test
    void foldersFillShowTitleAndSeason() {
        var g = ReleaseNameParser.parse("E03 - Il ritorno.mkv", List.of("Il Commissario Montalbano", "Stagione 2"));
        assertEquals(ContentType.TV, g.type());
        assertEquals("Il Commissario Montalbano", g.title());
        assertEquals(2, g.season());
        assertEquals(3, g.episode());

        var bare = ReleaseNameParser.parse("S01E05.mkv", List.of("Dark.2017.S01.1080p"));
        assertEquals("Dark", bare.title());
        assertEquals(1, bare.season());
        assertEquals(5, bare.episode());
    }

    @Test
    void episodeNumberWithoutSeasonFolderStaysAMovieGuess() {
        var g = ReleaseNameParser.parse("Rocky.Ep.4.mkv", List.of());
        assertEquals(ContentType.MOVIE, g.type());
    }

    @Test
    void folderNames() {
        var g = ReleaseNameParser.parseFolderName("Severance.S02.2160p.ATVP.WEB-DL");
        assertEquals(ContentType.TV, g.type());
        assertEquals("Severance", g.title());
        assertEquals(2, g.season());
        assertEquals("Some Movie", ReleaseNameParser.parseFolderName("Some.Movie.2020.1080p").title());
    }
}
