package com.github.stormino.service.link.archive;

import com.github.stormino.model.DownloadTask.ContentType;
import com.github.stormino.service.link.archive.ArchivePlacer.Entry;
import com.github.stormino.service.link.archive.ArchivePlacer.Placement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivePlacerTest {

    static final ArchivePlacer.Destinations DEST = new ArchivePlacer.Destinations() {
        @Override
        public String movie(String extension) {
            return "/movies/Film.2020" + extension;
        }

        @Override
        public String episode(int season, int episode, String extension) {
            return String.format("/tv/Show/Season %02d/Show - S%02dE%02d%s", season, season, episode, extension);
        }
    };

    static Entry e(String path, long size) {
        return new Entry(Path.of(path), size);
    }

    static Map<String, String> map(ArchivePlacer.Plan plan) {
        return plan.placements().stream().collect(Collectors.toMap(p -> p.source().toString(), Placement::destination));
    }

    @Test
    void movieTakesTheLargestVideoAndItsSubtitles() {
        var plan = ArchivePlacer.plan(List.of(
                        e("Film.2020.1080p/Film.2020.1080p.mkv", 4_000),
                        e("Film.2020.1080p/Sample/film-sample.mkv", 50),
                        e("Film.2020.1080p/Film.2020.1080p.ita.srt", 3),
                        e("Film.2020.1080p/Subs/English.srt", 3),
                        e("Film.2020.1080p/Film.2020.1080p.nfo", 1)),
                ContentType.MOVIE, "Film.2020.1080p", null, null, DEST);
        assertEquals(1, plan.videos());
        assertEquals(Map.of(
                "Film.2020.1080p/Film.2020.1080p.mkv", "/movies/Film.2020.mkv",
                "Film.2020.1080p/Film.2020.1080p.ita.srt", "/movies/Film.2020.ita.srt",
                "Film.2020.1080p/Subs/English.srt", "/movies/Film.2020.English.srt"), map(plan));
        assertTrue(plan.notes().isEmpty(), () -> plan.notes().toString());
    }

    @Test
    void seasonPackPlacesEachEpisodeAndReportsTheRest() {
        var plan = ArchivePlacer.plan(List.of(
                        e("Show.S02E01.mkv", 100),
                        e("Show.S02E02.mkv", 100),
                        e("Show.S02E02.720p.mkv", 60),
                        e("Show.S02E01.srt", 1),
                        e("Extras/Behind the scenes.mkv", 30),
                        e("E03 - Finale.mkv", 100)),
                ContentType.TV, "Show.S02.1080p", null, null, DEST);
        Map<String, String> m = map(plan);
        assertEquals(3, plan.videos());
        assertEquals("/tv/Show/Season 02/Show - S02E01.mkv", m.get("Show.S02E01.mkv"));
        assertEquals("/tv/Show/Season 02/Show - S02E02.mkv", m.get("Show.S02E02.mkv"), "the larger file wins");
        assertEquals("/tv/Show/Season 02/Show - S02E03.mkv", m.get("E03 - Finale.mkv"), "season from the archive name");
        assertEquals("/tv/Show/Season 02/Show - S02E01.srt", m.get("Show.S02E01.srt"));
        assertEquals(2, plan.notes().size(), () -> plan.notes().toString());
        assertTrue(plan.notes().stream().anyMatch(n -> n.startsWith("Show.S02E02.720p.mkv not placed: S02E02 is already")));
        assertTrue(plan.notes().stream().anyMatch(n -> n.startsWith("Behind the scenes.mkv not placed")));
    }

    @Test
    void singleEpisodeArchiveUsesTheGivenNumbers() {
        var plan = ArchivePlacer.plan(List.of(e("video.mkv", 10)), ContentType.TV, "whatever", 1, 7, DEST);
        assertEquals("/tv/Show/Season 01/Show - S01E07.mkv", map(plan).get("video.mkv"));
    }

    @Test
    void nothingToPlace() {
        var plan = ArchivePlacer.plan(List.of(e("readme.txt", 1)), ContentType.MOVIE, "x", null, null, DEST);
        assertEquals(0, plan.videos());
        assertEquals(List.of("The archive contains no video files"), plan.notes());
    }

    @Test
    void subtitleNamesDoNotCollide() {
        var plan = ArchivePlacer.plan(List.of(e("Film.mkv", 10), e("a/Film.srt", 1), e("b/Film.srt", 1)),
                ContentType.MOVIE, "x", null, null, DEST);
        assertEquals(Map.of("Film.mkv", "/movies/Film.2020.mkv", "a/Film.srt", "/movies/Film.2020.srt",
                "b/Film.srt", "/movies/Film.2020.2.srt"), map(plan));
    }

    @Test
    void subtitleSuffixes() {
        assertEquals(".ita", ArchivePlacer.subtitleSuffix("Movie.ita.srt", "Movie.mkv"));
        assertEquals(".en.forced", ArchivePlacer.subtitleSuffix("Movie.en.forced.srt", "Movie.mkv"));
        assertEquals(".it", ArchivePlacer.subtitleSuffix("subs_it.srt", "Movie.mkv"));
        assertEquals("", ArchivePlacer.subtitleSuffix("Movie.WEB.srt", "Other.mkv"));
        assertEquals("", ArchivePlacer.subtitleSuffix("Movie.srt", "Movie.mkv"));
    }
}
