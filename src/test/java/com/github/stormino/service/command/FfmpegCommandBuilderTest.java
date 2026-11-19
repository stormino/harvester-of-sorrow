package com.github.stormino.service.command;

import com.github.stormino.model.DownloadSubTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegCommandBuilderTest {

    final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

    static FfmpegCommandBuilder.AudioTrackInput audio(String lang, String title) {
        return new FfmpegCommandBuilder.AudioTrackInput(Path.of(lang + ".m4a"),
                DownloadSubTask.builder().language(lang).title(title).build());
    }

    static FfmpegCommandBuilder.SubtitleTrackInput sub(String lang, String title) {
        return new FfmpegCommandBuilder.SubtitleTrackInput(Path.of(title + ".vtt"),
                DownloadSubTask.builder().language(lang).title(title).build());
    }

    /** Value following {@code option} in the command. */
    static String arg(List<String> cmd, String option) {
        int i = cmd.indexOf(option);
        assertTrue(i >= 0, "missing " + option + " in " + cmd);
        return cmd.get(i + 1);
    }

    /** All values given to {@code option} (options like -metadata:s:a:0 repeat). */
    static List<String> args(List<String> cmd, String option) {
        return java.util.stream.IntStream.range(0, cmd.size() - 1)
                .filter(i -> cmd.get(i).equals(option)).mapToObj(i -> cmd.get(i + 1)).toList();
    }

    @Test
    void labelsEveryTrackAndPicksDefaults() {
        List<String> cmd = builder.buildMergeCommand(Path.of("video.mp4"),
                List.of(audio("eng", "English"), audio("ita", "Italian")),
                List.of(sub("ita", "Italian"), sub("ita", "Italian (Forced)"), sub("eng", "English (Forced)")),
                Path.of("out.mkv"), null, "it");

        assertEquals(List.of("language=eng", "title=English"), args(cmd, "-metadata:s:a:0"));
        assertEquals(List.of("language=ita", "title=Italian"), args(cmd, "-metadata:s:a:1"));
        // Italian is preferred, although it comes second
        assertEquals("0", arg(cmd, "-disposition:a:0"));
        assertEquals("default", arg(cmd, "-disposition:a:1"));

        assertEquals(List.of("language=ita", "title=Italian"), args(cmd, "-metadata:s:s:0"));
        assertEquals(List.of("language=ita", "title=Italian (Forced)"), args(cmd, "-metadata:s:s:1"));
        assertEquals("0", arg(cmd, "-disposition:s:0"));                 // full subtitles: off
        assertEquals("default+forced", arg(cmd, "-disposition:s:1"));    // forced, matches audio: on
        assertEquals("forced", arg(cmd, "-disposition:s:2"));            // forced, other language

        assertEquals("srt", arg(cmd, "-c:s"));
        assertEquals("passthrough", arg(cmd, "-default_mode"));
        assertEquals("out.mkv", cmd.get(cmd.size() - 1));
    }

    @Test
    void labelsAudioBuiltIntoTheVideo() {
        List<String> cmd = builder.buildMergeCommand(Path.of("video.mp4"), List.of(),
                List.of(sub("eng", "English")), Path.of("out.mkv"), "en", "it");

        assertEquals("0:a?", args(cmd, "-map").get(1));
        assertEquals(List.of("language=eng", "title=English"), args(cmd, "-metadata:s:a:0"));
        assertEquals("default", arg(cmd, "-disposition:a:0"));
        assertEquals("0", arg(cmd, "-disposition:s:0"));
    }

    @Test
    void mp4DestinationKeepsMovText() {
        List<String> cmd = builder.buildMergeCommand(Path.of("video.mp4"), List.of(audio("ita", "Italian")),
                List.of(sub("ita", "Italian")), Path.of("out.mp4"), null, "it");

        assertEquals("mov_text", arg(cmd, "-c:s"));
        assertFalse(cmd.contains("-default_mode"));
    }
}
