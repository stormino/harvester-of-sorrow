package com.github.stormino.service.link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseQualityTest {

    @Test
    void realNames() {
        var a = ReleaseQuality.parse("Una.Battaglia.Dopo.L.Altra.2025.WebRip.1080x264.ITA.AC3.Sub.Ita.Silvan.mkv");
        assertEquals(1080, a.resolution());
        assertEquals("WEBRip", a.source());
        assertEquals("AC3", a.audio());
        assertEquals(List.of("ITA"), a.languages());
        assertEquals(List.of("ITA"), a.subtitles());
        assertFalse(a.lowQuality());

        var b = ReleaseQuality.parse("Una.Battaglia.Dopo.L.Altra.2025.iTALiAN.BDRiP.x265.MP4-C78.mp4");
        assertEquals("BDRip", b.source());
        assertEquals("x265", b.codec());
        assertEquals(List.of("ITA"), b.languages());
        assertNull(b.resolution());

        var md = ReleaseQuality.parse("Una.Battaglia.Dopo.L.Altra.2025.iTALiAN.MD.WEBDL.XviD-WRS.avi");
        assertEquals("WEB-DL", md.source());
        assertEquals("XviD", md.codec());
        assertTrue(md.lowQuality(), "MD = mic-dubbed audio");

        var dvd = ReleaseQuality.parse("Una.Battaglia.Dopo.L.Altra.2025.DVD5.ITA.S-K.part1.rar");
        assertEquals("DVD5", dvd.source());

        var uhd = ReleaseQuality.parse("Dune.Part.Two.2024.2160p.UHD.BluRay.Remux.HDR.DV.TrueHD.Atmos.ITA.ENG-GRP.mkv");
        assertEquals(2160, uhd.resolution());
        assertEquals("Remux", uhd.source());
        assertTrue(uhd.hdr());
        assertEquals("TrueHD", uhd.audio());
        assertEquals(List.of("ITA", "ENG"), uhd.languages());

        assertTrue(ReleaseQuality.parse("Film.2025.TS.mkv").lowQuality());
        assertFalse(ReleaseQuality.parse("Show.S01E01.720p.mkv.ts").lowQuality(), ".ts extension is not telesync");
        assertNull(ReleaseQuality.parse("Blade.Runner.2049.mkv").resolution(), "2049 is a year, not a resolution");
    }

    @Test
    void scoresRankBetterReleasesHigher() {
        int uhd = ReleaseQuality.parse("X.2160p.BluRay.x265.mkv").score();
        int fhd = ReleaseQuality.parse("X.1080p.WEB-DL.x264.mkv").score();
        int sd = ReleaseQuality.parse("X.DVDRip.XviD.avi").score();
        int cam = ReleaseQuality.parse("X.1080p.CAM.mkv").score();
        assertTrue(uhd > fhd && fhd > sd && sd > cam);
    }
}
