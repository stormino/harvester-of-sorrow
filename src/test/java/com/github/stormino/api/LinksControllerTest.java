package com.github.stormino.api;

import com.github.stormino.api.v1.LinksControllerAccess;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.link.LinkFile;
import com.github.stormino.service.link.LinkResolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinksControllerTest {

    static LinkFile f(String name, long size, String... folders) {
        return new LinkFile("u:" + String.join("/", folders) + "/" + name, name, size, List.of(folders));
    }

    @Test
    void multiPartArchivesAreFoldedIntoOneEntry() {
        var groups = LinksControllerAccess.group(List.of(
                f("Show.S01.part2.rar", 10, "Pack"),
                f("Show.S01.part1.rar", 10, "Pack"),
                f("readme.txt", 1, "Pack"),
                f("Show.S01.part3.rar", 5, "Pack"),
                f("Movie.7z.001", 7), f("Movie.7z.002", 3),
                f("Single.zip", 4),
                f("Show.S01.part1.rar", 10, "Other")));
        assertEquals(List.of("Show.S01.part1.rar:3:25", "readme.txt:0:1", "Movie.7z.001:2:10", "Single.zip:0:4",
                "Show.S01.part1.rar:0:10"), groups);
    }

    @Test
    void archivesNamedLikeSeasonPacksAreGuessedAsTv() {
        var g = LinksControllerAccess.guess(f("Dark.S02.1080p.NF.part1.rar", 1));
        assertEquals(DownloadTask.ContentType.TV, g.type());
        assertEquals("Dark", g.title());
        assertEquals(2, g.season());
        var movie = LinksControllerAccess.guess(f("Heat.1995.1080p.rar", 1));
        assertEquals(DownloadTask.ContentType.MOVIE, movie.type());
        assertEquals("Heat", movie.title());
        assertEquals(1995, movie.year());

        var folder = LinksControllerAccess.overall(new LinkResolution("mega", "x", true, "Some upload",
                List.of(f("Dark.S02.1080p.part1.rar", 1), f("Dark.S02.1080p.part2.rar", 1)), 0));
        assertEquals(DownloadTask.ContentType.TV, folder.type());
        assertEquals("Some upload", folder.title());
    }
}
