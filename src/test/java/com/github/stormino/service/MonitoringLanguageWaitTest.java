package com.github.stormino.service;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.MediaInfo;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.persistence.MonitoredShowRepository;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** New episodes wait for audio in the default language, up to languageWaitDays after airing. */
class MonitoringLanguageWaitTest {

    static final MediaInfo ENGLISH_ONLY = new MediaInfo(
            List.of(new MediaInfo.Track("en", "English", false)), List.of(), List.of(720));
    static final MediaInfo WITH_ITALIAN = new MediaInfo(
            List.of(new MediaInfo.Track("it", "Italian", false), new MediaInfo.Track("en", "English", false)),
            List.of(), List.of(720));

    @TempDir
    Path library;
    MediaSourceProvider provider = mock(MediaSourceProvider.class);
    DownloadQueueService queue = mock(DownloadQueueService.class);
    MonitoringService service;
    MonitoredShow show;
    final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(library.resolve("Show.2026/Season 01"));
        Files.createFile(library.resolve("Show.2026/Season 01/Show.S01E01.mkv"));

        AppProperties properties = new AppProperties();
        properties.getDownload().setTvShowsPath(library.toString());
        properties.getDownload().setDefaultLanguage("it");

        MediaSourceRegistry registry = mock(MediaSourceRegistry.class);
        when(registry.get(MediaSource.VIXSRC)).thenReturn(provider);
        when(provider.supportedLanguages()).thenReturn(Set.of("it", "en"));
        when(queue.getAllTasks()).thenReturn(List.of());

        service = new MonitoringService(mock(MonitoredShowRepository.class), queue, registry, properties, new ObjectMapper());
        show = MonitoredShow.builder().id("1").title("Show").year(2026).tmdbId(42)
                .source(MediaSource.VIXSRC).sourceMetadata(new VixSrcMetadata(42, null, null))
                .directoryName("Show.2026").enabled(true).build();
    }

    EpisodeRef episode(int n, LocalDate aired) {
        return new EpisodeRef(1, n, "Episode " + n, new VixSrcMetadata(42, 1, n), aired);
    }

    void verifyQueued(int episode) {
        verify(queue).enqueueEpisode(argThat(m -> m.getEpisode() == episode), anyList(), anyString());
    }

    @Test
    void episodeWithItalianIsDownloaded() {
        when(provider.listEpisodes(any())).thenReturn(List.of(episode(2, today.minusDays(1))));
        when(provider.mediaInfo(any(), eq(1), eq(2))).thenReturn(Optional.of(WITH_ITALIAN));

        assertEquals(1, service.checkForNewEpisodes(show));
        verifyQueued(2);
    }

    @Test
    void recentEnglishOnlyEpisodeWaitsAndHoldsBackLaterOnes() {
        when(provider.listEpisodes(any())).thenReturn(List.of(
                episode(2, today.minusDays(2)), episode(3, today.minusDays(1))));
        when(provider.mediaInfo(any(), eq(1), eq(2))).thenReturn(Optional.of(ENGLISH_ONLY));
        when(provider.mediaInfo(any(), eq(1), eq(3))).thenReturn(Optional.of(WITH_ITALIAN));

        assertEquals(0, service.checkForNewEpisodes(show));
        verify(queue, never()).enqueueEpisode(any(), anyList(), anyString());
    }

    @Test
    void englishOnlyEpisodeIsDownloadedAfterTheWait() {
        when(provider.listEpisodes(any())).thenReturn(List.of(episode(2, today.minusDays(7))));
        when(provider.mediaInfo(any(), eq(1), eq(2))).thenReturn(Optional.of(ENGLISH_ONLY));

        assertEquals(1, service.checkForNewEpisodes(show));
        verifyQueued(2);
    }

    @Test
    void episodeTheSourceCannotDescribeIsNotHeldBack() {
        when(provider.listEpisodes(any())).thenReturn(List.of(episode(2, today)));
        when(provider.mediaInfo(any(), eq(1), eq(2))).thenReturn(Optional.empty());

        assertEquals(1, service.checkForNewEpisodes(show));
        verifyQueued(2);
    }
}
