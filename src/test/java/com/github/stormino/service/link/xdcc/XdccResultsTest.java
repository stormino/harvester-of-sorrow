package com.github.stormino.service.link.xdcc;

import com.github.stormino.model.DownloadTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XdccResultsTest {

    /** The real "una battaglia" results: file name, size, number of bots. */
    static final Object[][] REAL = {
            {"Clan.-.Scegli.Il.Tuo.Destino.1x10.Una.Nuova.Battaglia.ITA.RAI.WEB-DLRip.x264-UBi.mkv", "317M", 19},
            {"Una.Battaglia.Dopo.L.Altra.2025.iTALiAN.BDRiP.x264-PRiME.mkv", "2.5G", 15},
            {"Una.Battaglia.Dopo.L.Altra.2025.iTALiAN.MD.WEBDL.XviD-WRS.avi", "2.4G", 15},
            {"Orphan.Black.3x06.Una.Certa.Agonia.Del.Campo.Di.Battaglia.ITA.BDMux.x264-NovaRip.mkv", "350M", 14},
            {"Una.Battaglia.Dopo.L.Altra.2025.iTALiAN.BDRiP.x265.MP4-C78.mp4", "1.3G", 12},
            {"Una.Battaglia.Dopo.L.Altra.2025.WebRip.1080x264.ITA.AC3.Sub.Ita.Silvan.mkv", "3.1G", 10},
            {"Una.Battaglia.Dopo.L.Altra.2025.WEBRip.ITA.AC3.x264-ARH.mkv", "1.6G", 10},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD5.ITA.S-K.part1.rar", "1.9G", 7},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD5.ITA.S-K.part2.rar", "1.9G", 7},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD5.ITA.S-K.part3.rar", "640M", 7},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD9.ITA.S-K.part3.rar", "1.9G", 7},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD9.ITA.S-K.part4.rar", "1.9G", 7},
            {"Una.Battaglia.Dopo.L.Altra.2025.DVD9.ITA.S-K.part5.rar", "53M", 7},
            {"I.bon.bon.magici.di.Lilly.13.Una.battaglia.importante.mkv", "384M", 4},
            {"Una.per.tutte.tutte.per.una.-.1x04.-.La.Battaglia.Comincia.avi", "344M", 4},
    };

    static List<XdccHit> hits() {
        List<XdccHit> out = new ArrayList<>();
        for (Object[] r : REAL) {
            for (int b = 1; b <= (int) r[2]; b++) {
                String name = (String) r[0];
                XdccLink link = new XdccLink("irc.net.it", 6667, "#chan", "Bot" + b, b, "Net", name,
                        PwSearchEngine.approxBytes((String) r[1]));
                out.add(new XdccHit("xdcc.eu", "Net", "#chan", "Bot" + b, b, (String) r[1], link.size(), name, link));
            }
        }
        return out;
    }

    @Test
    void groupsTheRealResults() {
        var groups = XdccResults.groupWithoutPosters(hits(), "una battaglia", "ITA");
        var top = groups.get(0);
        assertEquals("Una Battaglia Dopo L Altra", top.title());
        assertEquals(2025, top.year());
        assertEquals(DownloadTask.ContentType.MOVIE, top.type());
        assertEquals(3, top.relevance());
        assertEquals(7, top.releases().size(), "5 files + 2 archive sets, bots merged");

        var best = top.releases().get(0);
        assertEquals("Una.Battaglia.Dopo.L.Altra.2025.WebRip.1080x264.ITA.AC3.Sub.Ita.Silvan.mkv", best.label());
        assertEquals(10, best.bots());
        assertEquals(9, best.link().mirrors().size());

        var dvd5 = top.releases().stream().filter(r -> r.label().contains("DVD5")).findFirst().orElseThrow();
        assertEquals("rar ×3", dvd5.format());
        assertTrue(dvd5.complete());
        assertEquals(3, dvd5.parts().size());
        var dvd9 = top.releases().stream().filter(r -> r.label().contains("DVD9")).findFirst().orElseThrow();
        assertFalse(dvd9.complete());
        assertEquals(List.of(1, 2), dvd9.missing());
        assertEquals(dvd9, top.releases().get(top.releases().size() - 1), "incomplete sets go last");
        var md = top.releases().stream().filter(r -> r.label().contains(".MD.")).findFirst().orElseThrow();
        assertTrue(top.releases().indexOf(md) >= 4, "mic-dubbed releases sink");

        assertTrue(groups.stream().skip(1).allMatch(g -> g.relevance() < 3), "other titles only contain the words");
        assertTrue(groups.stream().anyMatch(g -> g.title().startsWith("Clan") && g.type() == DownloadTask.ContentType.TV));
        assertEquals(15, hits().stream().map(XdccHit::fileName).distinct().count());
    }

    @Test
    void relevance() {
        assertEquals(3, XdccResults.relevance("una battaglia", "Una Battaglia Dopo L'Altra"));
        assertEquals(2, XdccResults.relevance("dopo l altra", "Una Battaglia Dopo L Altra"));
        assertEquals(1, XdccResults.relevance("altra una", "Una Battaglia Dopo L Altra"));
        assertEquals(0, XdccResults.relevance("una battaglia", "Clan Scegli Il Tuo Destino"));
    }

    static List<XdccHit> hits(Object[][] rows) {
        List<XdccHit> out = new ArrayList<>();
        for (Object[] r : rows) {
            for (int b = 1; b <= (int) r[2]; b++) {
                String name = (String) r[0];
                XdccLink link = new XdccLink("irc.net.it", 6667, "#chan", "Bot" + b, b, "Net", name,
                        PwSearchEngine.approxBytes((String) r[1]));
                out.add(new XdccHit("xdcc.eu", "Net", "#chan", "Bot" + b, b, (String) r[1], link.size(), name, link));
            }
        }
        return out;
    }

    /** "Curious George" (2006) is a film, a TV show and a game: real xdcc.eu packs. */
    @Test
    void filmsAndEpisodesOfTheSameTitleStayApart() {
        Object[][] rows = {
                {"Curious George (2006) Bluray-1080p.mkv", "4.4G", 1},
                {"[ irc.coreirc.net ] Curious George 2006 NORDiC 1080p WEB-DL DDP2 0 H 264-ADDICTION", "2.5G", 2},
                {"Curious.George.REPACK-RELOADED.tar", "2.0G", 3},
                {"Curious.George.S15E11.720p.HEVC.x265-MeGusta.mkv", "184M", 1},
                {"Curious.George.S15E13.1080p.HEVC.x265-MeGusta.mkv", "248M", 1},
                {"Curious.George.S03.1080p.WEB-DL.x264-GRP.mkv", "9.0G", 1},
        };
        var groups = XdccResults.groupWithoutPosters(hits(rows), "curious george", "ITA");
        assertEquals(2, groups.size(), "film and show apart, game dropped");

        var film = groups.stream().filter(g -> g.type() == DownloadTask.ContentType.MOVIE).findFirst().orElseThrow();
        assertEquals("Curious George", film.title());
        assertEquals(2006, film.year());
        assertEquals(2, film.releases().size());
        assertTrue(film.releases().stream().allMatch(r -> r.episode() == null));
        var web = film.releases().stream().filter(r -> r.label().contains("NORDiC")).findFirst().orElseThrow();
        assertEquals("Curious George 2006 NORDiC 1080p WEB-DL DDP2 0 H 264-ADDICTION", web.label(), "network prefix dropped");
        assertEquals("?", web.format(), "no extension, not \"net ] curious…\"");

        var show = groups.stream().filter(g -> g.type() == DownloadTask.ContentType.TV).findFirst().orElseThrow();
        assertEquals(3, show.releases().size(), "two episodes and a season pack");
        assertTrue(show.releases().stream().noneMatch(r -> r.label().contains("Bluray")));
    }

    @Test
    void notVideo() {
        assertTrue(XdccResults.notVideo("Curious.George.REPACK-RELOADED.tar"));
        assertTrue(XdccResults.notVideo("Some.Game-CODEX.part01.rar"));
        assertTrue(XdccResults.notVideo("Some Game [FitGirl Repack].zip"));
        assertTrue(XdccResults.notVideo("Album - Artist.flac"));
        assertTrue(XdccResults.notVideo("setup.exe"));
        assertFalse(XdccResults.notVideo("Reloaded.2003.1080p.BluRay.x264-GRP.mkv"));
        assertFalse(XdccResults.notVideo("The.Matrix.Reloaded.2003.ITA.mkv"));
        assertFalse(XdccResults.notVideo("Una.Battaglia.Dopo.L.Altra.2025.DVD5.ITA.S-K.part1.rar"));
    }
}
