package com.github.stormino.service;

import com.github.stormino.model.DownloadResult;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.strategy.TrackDownloadRequest;
import com.github.stormino.service.strategy.TrackDownloadStrategy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubtitleTrackDownloadStrategy implements TrackDownloadStrategy {

    private final HlsParserService hlsParser;
    private final HlsSegmentDownloader segmentDownloader;
    private final OkHttpClient httpClient;

    @Override
    public DownloadResult downloadTrack(TrackDownloadRequest request) {
        return downloadSubtitleTrack(
                request.getPlaylistUrl(),
                request.getReferer(),
                request.getOutputFile(),
                request.getLanguage(),
                request.getMaxConcurrency(),
                request.getSubTask(),
                request.getParentTaskId(),
                request.getProgressCallback()
        );
    }

    @Override
    public TrackType getTrackType() {
        return TrackType.SUBTITLE;
    }

    /**
     * Download subtitle track for specific language from HLS playlist
     */
    public DownloadResult downloadSubtitleTrack(
            @NonNull String playlistUrl,
            @NonNull String referer,
            @NonNull Path outputFile,
            @NonNull String language,
            int maxConcurrent,
            @NonNull DownloadSubTask subTask,
            @NonNull String parentTaskId,
            Consumer<ProgressUpdate> progressCallback) {

        log.debug("Starting subtitle track download for language: {}", language);

        try {
            // Direct subtitle files (e.g. RaiPlay's *.srt) bypass HLS parsing.
            if (isDirectSubtitleFile(playlistUrl)) {
                return downloadDirectSubtitle(playlistUrl, referer, outputFile, language, subTask);
            }

            // 1. Parse master playlist
            Optional<HlsParserService.HlsPlaylist> playlistOpt = hlsParser.parsePlaylist(playlistUrl, referer);
            if (playlistOpt.isEmpty()) {
                log.error("Failed to parse master playlist");
                return DownloadResult.failure("Failed to parse master playlist");
            }

            HlsParserService.HlsPlaylist playlist = playlistOpt.get();

            // 2. Select subtitle track for language
            String subtitlePlaylistUrl;

            if (playlist.getType() == HlsParserService.PlaylistType.MASTER) {
                HlsParserService.SubtitleTrack selectedTrack = selectSubtitleTrack(
                        playlist.getSubtitleTracks(), language);

                if (selectedTrack == null) {
                    log.debug("No subtitle track available for language: {} (skipping)", language);
                    subTask.setStatus(DownloadStatus.NOT_FOUND);
                    subTask.setErrorMessage("Track not available for this language");
                    return DownloadResult.notFound("Track not available for this language");
                }

                log.debug("Selected subtitle track: {}", selectedTrack.getName());
                subtitlePlaylistUrl = selectedTrack.getUrl();

                // Set track title for metadata
                if (selectedTrack.getName() != null && subTask != null) {
                    subTask.setTitle(selectedTrack.getName());
                }

            } else {
                // Already a media playlist - assume it's the right language
                subtitlePlaylistUrl = playlistUrl;
            }

            // 3. Parse subtitle media playlist for segments and encryption info
            Optional<HlsParserService.MediaPlaylistInfo> playlistInfoOpt =
                    hlsParser.parseMediaPlaylistInfo(subtitlePlaylistUrl, referer);
            if (playlistInfoOpt.isEmpty()) {
                log.error("Failed to parse subtitle playlist info");
                return DownloadResult.failure("Failed to parse subtitle playlist info");
            }

            HlsParserService.MediaPlaylistInfo playlistInfo = playlistInfoOpt.get();
            List<String> segments = playlistInfo.getSegments();
            HlsParserService.EncryptionInfo encryption = playlistInfo.getEncryption();

            log.debug("Found {} subtitle segments for {}, encrypted={}", segments.size(), language, encryption != null);

            // 4. Download segments with progress tracking
            Path tempSubtitleFile = outputFile.getParent().resolve(outputFile.getFileName() + ".temp");

            HlsSegmentDownloader.SegmentDownloadResult result = segmentDownloader.downloadSegments(
                    segments,
                    tempSubtitleFile,
                    referer,
                    maxConcurrent,
                    encryption,
                    progress -> {
                        // Update sub-task progress
                        subTask.setProgress(progress.getPercentage());

                        // Broadcast progress
                        if (progressCallback != null) {
                            ProgressUpdate update = ProgressUpdate.builder()
                                    .taskId(parentTaskId)
                                    .subTaskId(subTask.getId())
                                    .status(DownloadStatus.DOWNLOADING)
                                    .progress(progress.getPercentage())
                                    .message(progress.getCurrentSegment())
                                    .build();
                            progressCallback.accept(update);
                        }
                    }
            );

            if (!result.isSuccess()) {
                log.error("Subtitle track download failed: {}", result.getErrorMessage());
                return DownloadResult.failure("Subtitle track download failed: " + result.getErrorMessage());
            }

            // 5. Convert to proper WebVTT/SRT if needed
            convertSubtitleFormat(tempSubtitleFile, outputFile);
            Files.deleteIfExists(tempSubtitleFile);

            log.debug("Subtitle track download completed: {}", outputFile);
            return DownloadResult.success();

        } catch (Exception e) {
            log.error("Failed to download subtitle track: {}", e.getMessage(), e);
            return DownloadResult.failure("Failed to download subtitle track: " + e.getMessage(), e);
        }
    }

    private HlsParserService.SubtitleTrack selectSubtitleTrack(
            List<HlsParserService.SubtitleTrack> subtitleTracks, String language) {

        if (subtitleTracks == null || subtitleTracks.isEmpty()) {
            return null;
        }

        // Try exact match first
        for (HlsParserService.SubtitleTrack track : subtitleTracks) {
            if (track.getLanguage() != null &&
                track.getLanguage().equalsIgnoreCase(language)) {
                return track;
            }
        }

        // Try partial match (ISO 639-1 vs ISO 639-2: "it" vs "ita", "en" vs "eng")
        for (HlsParserService.SubtitleTrack track : subtitleTracks) {
            if (track.getLanguage() != null) {
                String trackLang = track.getLanguage().toLowerCase();
                String requestLang = language.toLowerCase();

                // Match if either starts with the other (handle 2-char vs 3-char codes)
                if (trackLang.startsWith(requestLang) || requestLang.startsWith(trackLang)) {
                    log.debug("Matched subtitle track {} to requested language {}", trackLang, requestLang);
                    return track;
                }
            }
        }

        // No match found
        log.warn("No subtitle track found for language: {}", language);
        return null;
    }

    /**
     * Direct file URL (e.g. {@code .../foo.srt}) — no HLS segmentation,
     * fetch in one shot.
     */
    private boolean isDirectSubtitleFile(String url) {
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".srt") || lower.endsWith(".vtt");
    }

    private DownloadResult downloadDirectSubtitle(String url, String referer, Path outputFile,
                                                  String language, DownloadSubTask subTask) {
        log.debug("Downloading direct subtitle for language {}: {}", language, url);
        Request.Builder rb = new Request.Builder().url(url).addHeader("Accept", "*/*");
        if (referer != null) rb.addHeader("Referer", referer);

        try (Response response = httpClient.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("Direct subtitle fetch HTTP {} for {}", response.code(), url);
                return DownloadResult.failure("Subtitle fetch failed: HTTP " + response.code());
            }
            String body = response.body().string();
            String vtt  = url.toLowerCase().contains(".srt") ? srtToVtt(body) : body;
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, vtt, StandardCharsets.UTF_8);
            subTask.setProgress(100.0);
            log.debug("Direct subtitle saved to {}", outputFile);
            return DownloadResult.success();
        } catch (IOException e) {
            log.error("Direct subtitle download failed: {}", e.getMessage(), e);
            return DownloadResult.failure("Subtitle download failed: " + e.getMessage(), e);
        }
    }

    /**
     * Minimal SRT → WebVTT: prepend the {@code WEBVTT} header and replace the
     * comma separator in timestamps ({@code HH:MM:SS,mmm}) with a period.
     */
    static String srtToVtt(String srt) {
        String normalized = srt.replaceAll(
                "(\\d{2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2");
        if (normalized.startsWith("WEBVTT")) return normalized;
        return "WEBVTT\n\n" + normalized.stripLeading();
    }

    /**
     * Convert concatenated WebVTT segments to proper WebVTT format
     * by removing duplicate headers and ensuring proper formatting
     */
    private void convertSubtitleFormat(Path inputFile, Path outputFile) throws IOException {
        // Read all lines from concatenated file
        List<String> lines = Files.readAllLines(inputFile);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            boolean headerWritten = false;
            boolean skipNextBlank = false;

            for (String line : lines) {
                // Write WEBVTT header only once at the beginning
                if (line.startsWith("WEBVTT")) {
                    if (!headerWritten) {
                        writer.write(line);
                        writer.newLine();
                        headerWritten = true;
                        skipNextBlank = true;
                    }
                    continue;
                }

                // Skip blank line immediately after header duplicates
                if (skipNextBlank && line.trim().isEmpty()) {
                    skipNextBlank = false;
                    continue;
                }

                skipNextBlank = false;

                // Write all other lines (cues, timestamps, text)
                writer.write(line);
                writer.newLine();
            }
        }

        log.debug("Converted subtitle format from {} to {}", inputFile, outputFile);
    }
}
