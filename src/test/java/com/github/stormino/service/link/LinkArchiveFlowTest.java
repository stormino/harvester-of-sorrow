package com.github.stormino.service.link;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.LinkMetadata;
import com.github.stormino.persistence.TaskPersistenceService;
import com.github.stormino.service.FileCopyService;
import com.github.stormino.service.ProgressBroadcastService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.link.archive.ArchiveExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Season pack as a multi-part RAR, end to end: download parts, unpack, place episodes and subtitles. */
@EnabledIf("toolsInstalled")
class LinkArchiveFlowTest {

    @TempDir
    Path dir;

    static boolean toolsInstalled() {
        return Stream.of("rar", "unrar", "rsync").allMatch(t -> {
            try {
                return new ProcessBuilder(t, "--version").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() >= 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** Serves files from a local "remote" folder. */
    record LocalProvider(Path remote, List<String> requested) implements LinkProvider {
        @Override public String id() { return "local"; }
        @Override public String displayName() { return "Local"; }
        @Override public int matchScore(String url) { return url.startsWith("local:") ? SCORE_SPECIFIC : SCORE_NONE; }
        @Override public LinkResolution resolve(String url) { throw new UnsupportedOperationException(); }
        @Override
        public Path download(LinkFile file, Path workDir, String targetName, LinkProgress progress, LinkControl control) {
            try {
                requested.add(targetName);
                Files.createDirectories(workDir);
                Path target = workDir.resolve(targetName);
                Files.copy(remote.resolve(file.url().substring("local:".length())), target);
                progress.update(Files.size(target), Files.size(target), 1);
                return target;
            } catch (Exception e) {
                throw new LinkException(e.getMessage(), e);
            }
        }
    }

    @Test
    void seasonPackIsUnpackedAndPlacedPerEpisode() throws Exception {
        Path pack = Files.createDirectories(dir.resolve("src/Show.S01.1080p"));
        byte[][] videos = new byte[3][];
        for (int i = 1; i <= 2; i++) {
            videos[i] = new byte[200_000];
            new Random(i).nextBytes(videos[i]);
            Files.write(pack.resolve("Show.S01E0" + i + ".1080p.mkv"), videos[i]);
        }
        Files.writeString(pack.resolve("Show.S01E01.1080p.ita.srt"), "1\n00:00:01,000 --> 00:00:02,000\nCiao\n");
        Files.writeString(pack.resolve("Show.S01.nfo"), "release info");
        Path remote = Files.createDirectories(dir.resolve("remote"));
        Process p = new ProcessBuilder("rar", "a", "-idq", "-v150k", remote.resolve("Show.S01.1080p.rar").toString(),
                "Show.S01.1080p").directory(dir.resolve("src").toFile()).inheritIO().start();
        assertEquals(0, p.waitFor());
        List<LinkMetadata.Part> parts;
        try (Stream<Path> s = Files.list(remote)) {
            parts = s.sorted().map(f -> new LinkMetadata.Part("local:" + f.getFileName(), f.getFileName().toString(),
                    sizeOf(f))).toList();
        }
        assertTrue(parts.size() >= 3, "multi-part");

        AppProperties props = new AppProperties();
        props.getDownload().setTempPath(dir.resolve("temp").toString());
        props.getDownload().setTvShowsPath(dir.resolve("tv").toString());
        List<String> requested = new ArrayList<>();
        LinkDownloadService service = new LinkDownloadService(
                new LinkProviderRegistry(List.of(new LocalProvider(remote, requested))),
                new ArchiveExtractor(), new LinkMatchService(new TmdbMetadataService(null)), props,
                new FileCopyService(), mock(TaskPersistenceService.class), mock(ProgressBroadcastService.class));

        DownloadTask task = DownloadTask.builder()
                .source(MediaSource.LINK)
                .sourceMetadata(new LinkMetadata("local", "local:folder", parts.get(0).url(), parts.get(0).fileName(),
                        null, parts))
                .contentType(DownloadTask.ContentType.TV)
                .title("Show").year(2020).season(1)
                .outputPath(dir.resolve("tv/Show.2020").toString())
                .languages(List.of("it"))
                .build();
        task.setStatus(DownloadStatus.DOWNLOADING);

        assertTrue(service.run(task), task::getErrorMessage);
        assertEquals(parts.stream().map(LinkMetadata.Part::fileName).toList(), requested, "volumes keep their names");
        Path season = dir.resolve("tv/Show.2020/Season 01");
        assertArrayEquals(videos[1], Files.readAllBytes(season.resolve("Show.-.S01E01.mkv")));
        assertArrayEquals(videos[2], Files.readAllBytes(season.resolve("Show.-.S01E02.mkv")));
        assertTrue(Files.exists(season.resolve("Show.-.S01E01.ita.srt")));
        try (Stream<Path> s = Files.list(season)) {
            assertEquals(3, s.count(), "no .nfo or other leftovers");
        }
        assertFalse(Files.exists(dir.resolve("temp/links/" + task.getId())), "work dir removed");
        assertEquals(null, task.getErrorMessage());
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
