package com.github.stormino.service.command;

import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.util.DownloadConstants;
import com.github.stormino.util.LanguageCodes;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for constructing ffmpeg command-line arguments.
 * Centralizes all ffmpeg command construction logic for consistency and testability.
 */
@Component
@Slf4j
public class FfmpegCommandBuilder {

    /**
     * Build ffmpeg command for converting video TS to MP4.
     *
     * @param inputFile Input TS file
     * @param outputFile Output MP4 file
     * @return ffmpeg command arguments
     */
    public List<String> buildVideoConversionCommand(@NonNull Path inputFile, @NonNull Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL);
        command.add("-stats");
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-c");
        command.add("copy");
        command.add("-bsf:a");
        command.add(DownloadConstants.FFMPEG_AAC_BSF);
        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built video conversion command: {}", String.join(" ", command));
        return command;
    }

    /**
     * Build ffmpeg command for converting audio TS to M4A.
     *
     * @param inputFile Input TS file
     * @param outputFile Output M4A file
     * @return ffmpeg command arguments
     */
    public List<String> buildAudioConversionCommand(@NonNull Path inputFile, @NonNull Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL);
        command.add("-stats");
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-c");
        command.add("copy");
        command.add("-vn");  // No video
        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built audio conversion command: {}", String.join(" ", command));
        return command;
    }

    /**
     * Build the ffmpeg command that assembles the final file from the downloaded tracks.
     *
     * <p>Every audio and subtitle track gets a three-letter language tag and a name
     * ("Italian", "Italian (Forced)"). Only the preferred audio language is marked default;
     * subtitles are off by default, except forced ones in the default audio language, which
     * players show automatically. Names and the forced flag only survive in Matroska, so
     * the library format is MKV; an MP4 destination (tasks queued before 7.2.0) still works.
     *
     * @param videoFile             video track (may carry built-in audio)
     * @param audioTracks           separate audio tracks
     * @param subtitleTracks        subtitle tracks; a "(Forced)" title marks a forced one
     * @param outputFile            final file; its extension picks the container
     * @param embeddedAudioLanguage language of the audio built into the video, when there are
     *                              no separate audio tracks; null if unknown
     * @param preferredLanguage     audio language to mark as default (e.g. "it")
     */
    public List<String> buildMergeCommand(
            @NonNull Path videoFile,
            @NonNull List<AudioTrackInput> audioTracks,
            @NonNull List<SubtitleTrackInput> subtitleTracks,
            @NonNull Path outputFile,
            String embeddedAudioLanguage,
            String preferredLanguage) {

        boolean mkv = outputFile.getFileName().toString().toLowerCase().endsWith(".mkv");

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL_MERGE);
        command.add("-stats");

        command.add("-i");
        command.add(videoFile.toString());
        for (AudioTrackInput audioTrack : audioTracks) {
            command.add("-i");
            command.add(audioTrack.getFile().toString());
        }
        for (SubtitleTrackInput subtitleTrack : subtitleTracks) {
            command.add("-i");
            command.add(subtitleTrack.getFile().toString());
        }

        command.add("-map");
        command.add("0:v:0");
        if (audioTracks.isEmpty()) {
            // No separate audio tracks: keep the audio built into the video ("?" = optional)
            command.add("-map");
            command.add("0:a?");
        } else {
            for (int i = 1; i <= audioTracks.size(); i++) {
                command.add("-map");
                command.add(i + ":a:0");
            }
        }
        int subtitleInputOffset = 1 + audioTracks.size();
        for (int i = 0; i < subtitleTracks.size(); i++) {
            command.add("-map");
            command.add((subtitleInputOffset + i) + ":s:0");
        }

        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("copy");
        if (!subtitleTracks.isEmpty()) {
            command.add("-c:s");
            command.add(mkv ? DownloadConstants.FFMPEG_SUBTITLE_CODEC_MKV : DownloadConstants.FFMPEG_SUBTITLE_CODEC);
        }

        // Audio: language + name on every track; default = the preferred language
        String defaultAudioLanguage;
        if (audioTracks.isEmpty()) {
            defaultAudioLanguage = embeddedAudioLanguage;
            if (embeddedAudioLanguage != null) {
                addStreamMetadata(command, "a", 0, LanguageCodes.toIso3(embeddedAudioLanguage),
                        LanguageCodes.displayName(embeddedAudioLanguage));
            }
            command.add("-disposition:a:0");
            command.add("default");
        } else {
            int defaultIndex = 0;
            for (int i = 0; i < audioTracks.size(); i++) {
                if (LanguageCodes.same(audioTracks.get(i).getLanguage(), preferredLanguage)) {
                    defaultIndex = i;
                    break;
                }
            }
            defaultAudioLanguage = audioTracks.get(defaultIndex).getLanguage();
            for (int i = 0; i < audioTracks.size(); i++) {
                AudioTrackInput audioTrack = audioTracks.get(i);
                String title = audioTrack.getTitle() != null && !audioTrack.getTitle().isBlank()
                        ? audioTrack.getTitle() : LanguageCodes.displayName(audioTrack.getLanguage());
                addStreamMetadata(command, "a", i, LanguageCodes.toIso3(audioTrack.getLanguage()), title);
                command.add("-disposition:a:" + i);
                command.add(i == defaultIndex ? "default" : "0");
            }
        }

        // Subtitles: off by default; forced ones flagged, and shown by default when they
        // match the default audio language (they only translate foreign lines and signs).
        for (int i = 0; i < subtitleTracks.size(); i++) {
            SubtitleTrackInput subtitleTrack = subtitleTracks.get(i);
            boolean forced = subtitleTrack.isForced();
            String title = subtitleTrack.getTitle() != null && !subtitleTrack.getTitle().isBlank()
                    ? subtitleTrack.getTitle() : LanguageCodes.displayName(subtitleTrack.getLanguage());
            addStreamMetadata(command, "s", i, LanguageCodes.toIso3(subtitleTrack.getLanguage()), title);
            String disposition = "0";
            if (forced) {
                disposition = LanguageCodes.same(subtitleTrack.getLanguage(), defaultAudioLanguage)
                        ? "default+forced" : "forced";
            }
            command.add("-disposition:s:" + i);
            command.add(disposition);
        }

        if (mkv) {
            // Otherwise the Matroska muxer marks the first track of each type default on its own
            command.add("-default_mode");
            command.add("passthrough");
        }

        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built merge command: {}", String.join(" ", command));
        return command;
    }

    private static void addStreamMetadata(List<String> command, String type, int index, String language, String title) {
        command.add("-metadata:s:" + type + ":" + index);
        command.add("language=" + language);
        if (title != null) {
            command.add("-metadata:s:" + type + ":" + index);
            command.add("title=" + title);
        }
    }

    /**
     * Input for audio track in merge operation.
     */
    public static class AudioTrackInput {
        private final Path file;
        private final String language;
        private final String title;

        public AudioTrackInput(Path file, DownloadSubTask subTask) {
            this.file = file;
            this.language = subTask.getLanguage();
            this.title = subTask.getTitle();
        }

        public Path getFile() {
            return file;
        }

        public String getLanguage() {
            return language;
        }

        public String getTitle() {
            return title;
        }
    }

    /**
     * Input for subtitle track in merge operation.
     */
    public static class SubtitleTrackInput {
        private final Path file;
        private final String language;
        private final String title;

        public SubtitleTrackInput(Path file, DownloadSubTask subTask) {
            this.file = file;
            this.language = subTask.getLanguage();
            this.title = subTask.getTitle();
        }

        /** Forced tracks are named "... (Forced)" by the orchestrator. */
        public boolean isForced() {
            return title != null && title.toLowerCase().contains("forced");
        }

        public Path getFile() {
            return file;
        }

        public String getLanguage() {
            return language;
        }

        public String getTitle() {
            return title;
        }
    }
}
