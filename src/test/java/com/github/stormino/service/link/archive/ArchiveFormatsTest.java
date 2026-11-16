package com.github.stormino.service.link.archive;

import com.github.stormino.service.link.archive.ArchiveFormats.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveFormatsTest {

    @ParameterizedTest
    @CsvSource({
            "Show.S01.part1.rar,     Show.S01,       RAR,       1",
            "Show.S01.part02.rar,    Show.S01,       RAR,       2",
            "Movie.rar,              Movie,          RAR,       0",
            "Movie.r00,              Movie,          RAR,       1",
            "Movie.r01,              Movie,          RAR,       2",
            "Movie.7z.001,           Movie.7z,       SEVEN_ZIP, 1",
            "Movie.zip.002,          Movie.zip,      SEVEN_ZIP, 2",
            "Movie.7z,               Movie.7z,       SEVEN_ZIP, 0",
            "MOVIE.ZIP,              MOVIE.zip,      SEVEN_ZIP, 0",
            "Movie.z01,              Movie.zip,      SEVEN_ZIP, 1",
    })
    void volumes(String name, String set, Kind kind, int order) {
        var v = ArchiveFormats.volume(name).orElseThrow();
        assertEquals(set, v.set());
        assertEquals(kind, v.kind());
        assertEquals(order, v.order());
    }

    @Test
    void nonArchives() {
        assertFalse(ArchiveFormats.isArchive("Movie.mkv"));
        assertFalse(ArchiveFormats.isArchive("Movie.2020.1080p.mp4"));
        assertFalse(ArchiveFormats.isArchive("subs.srt"));
        assertFalse(ArchiveFormats.isArchive(null));
        assertTrue(ArchiveFormats.isArchive("x.rar"));
    }

    @Test
    void baseNames() {
        assertEquals("Show.S01", ArchiveFormats.baseName("Show.S01.part1.rar"));
        assertEquals("Movie.2020", ArchiveFormats.baseName("Movie.2020.7z.001"));
        assertEquals("Movie.2020", ArchiveFormats.baseName("Movie.2020.zip"));
        assertEquals("Movie.mkv", ArchiveFormats.baseName("Movie.mkv"));
    }
}
