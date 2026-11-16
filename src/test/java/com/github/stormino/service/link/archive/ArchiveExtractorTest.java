package com.github.stormino.service.link.archive;

import com.github.stormino.service.link.LinkControl;
import com.github.stormino.service.link.LinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real tools; skipped where 7-Zip/unrar/rar are not installed (rar only creates the test archives). */
@EnabledIf("toolsInstalled")
class ArchiveExtractorTest {

    @TempDir
    Path dir;
    Path src;
    byte[] video;
    final ArchiveExtractor extractor = new ArchiveExtractor();
    static final String SZ = ArchiveExtractor.sevenZipBinary();

    static boolean toolsInstalled() {
        return Stream.of(ArchiveExtractor.sevenZipBinary(), "unrar", "rar").allMatch(t -> {
            try {
                return new ProcessBuilder(t).redirectErrorStream(true).start().waitFor() >= 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @BeforeEach
    void setUp() throws IOException {
        src = Files.createDirectories(dir.resolve("src/Show.S01"));
        video = new byte[400_000];
        new Random(7).nextBytes(video);
        Files.write(src.resolve("Show.S01E01.mkv"), video);
        Files.writeString(src.resolve("Show.S01E01.ita.srt"), "1\n00:00:01,000 --> 00:00:02,000\nCiao\n");
    }

    private void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), () -> String.join(" ", cmd) + "\n" + out);
    }

    private List<Path> unpack(Path archive, ArchiveFormats.Kind kind) throws IOException {
        Path out = dir.resolve("out");
        List<Integer> progress = new ArrayList<>();
        extractor.extract(archive, kind, out, progress::add, new LinkControl());
        assertTrue(progress.contains(100));
        try (Stream<Path> walk = Files.walk(out)) {
            return walk.filter(Files::isRegularFile).map(out::relativize).sorted().toList();
        }
    }

    @Test
    void zip() throws Exception {
        run(dir.resolve("src"), SZ, "a", "-bso0", "../pack.zip", "Show.S01");
        assertEquals(List.of(Path.of("Show.S01/Show.S01E01.ita.srt"), Path.of("Show.S01/Show.S01E01.mkv")),
                unpack(dir.resolve("pack.zip"), ArchiveFormats.Kind.SEVEN_ZIP));
        assertArrayEquals(video, Files.readAllBytes(dir.resolve("out/Show.S01/Show.S01E01.mkv")));
    }

    @Test
    void split7z() throws Exception {
        run(dir.resolve("src"), SZ, "a", "-bso0", "-v150k", "../pack.7z", "Show.S01");
        assertTrue(Files.exists(dir.resolve("pack.7z.003")));
        unpack(dir.resolve("pack.7z.001"), ArchiveFormats.Kind.SEVEN_ZIP);
        assertArrayEquals(video, Files.readAllBytes(dir.resolve("out/Show.S01/Show.S01E01.mkv")));
    }

    @Test
    void multiPartRar() throws Exception {
        run(dir.resolve("src"), "rar", "a", "-idq", "-v150k", "../pack.rar", "Show.S01");
        assertTrue(Files.exists(dir.resolve("pack.part3.rar")));
        unpack(dir.resolve("pack.part1.rar"), ArchiveFormats.Kind.RAR);
        assertArrayEquals(video, Files.readAllBytes(dir.resolve("out/Show.S01/Show.S01E01.mkv")));
    }

    @Test
    void passwordProtectedArchivesAreRefused() throws Exception {
        run(dir.resolve("src"), SZ, "a", "-bso0", "-psecret", "../enc.zip", "Show.S01");
        LinkException zip = assertThrows(LinkException.class,
                () -> extractor.extract(dir.resolve("enc.zip"), ArchiveFormats.Kind.SEVEN_ZIP, dir.resolve("o1"), p -> { }, new LinkControl()));
        assertEquals("The archive is password-protected", zip.getMessage());

        run(dir.resolve("src"), "rar", "a", "-idq", "-hpsecret", "../enc.rar", "Show.S01");
        LinkException rar = assertThrows(LinkException.class,
                () -> extractor.extract(dir.resolve("enc.rar"), ArchiveFormats.Kind.RAR, dir.resolve("o2"), p -> { }, new LinkControl()));
        assertEquals("The archive is password-protected", rar.getMessage());
    }

    @Test
    void missingVolumesAreReported() throws Exception {
        run(dir.resolve("src"), "rar", "a", "-idq", "-v150k", "../pack.rar", "Show.S01");
        Files.delete(dir.resolve("pack.part2.rar"));
        LinkException e = assertThrows(LinkException.class,
                () -> extractor.extract(dir.resolve("pack.part1.rar"), ArchiveFormats.Kind.RAR, dir.resolve("o"), p -> { }, new LinkControl()));
        assertEquals("The archive is incomplete (missing or truncated parts)", e.getMessage());
    }
}
