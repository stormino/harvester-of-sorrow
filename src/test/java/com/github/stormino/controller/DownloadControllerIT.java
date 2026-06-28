package com.github.stormino.controller;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DownloadController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("DownloadController integration tests")
class DownloadControllerIT {

    @Autowired
    MockMvc mvc;

    @MockBean
    DownloadQueueService downloadQueueService;
    @MockBean
    TmdbMetadataService metadataService;
    @MockBean
    MediaSourceRegistry sourceRegistry;

    private DownloadTask sampleTask;

    @BeforeEach
    void setUp() {
        sampleTask = DownloadTask.builder()
                .id("test-uuid")
                .source(MediaSource.VIXSRC)
                .title("Test Movie")
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/search
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/search")
    class SearchTests {

        @Test
        @DisplayName("returns 200 with results from all providers when no source filter")
        void searchAllSources() throws Exception {
            MediaSourceProvider provider = mock(MediaSourceProvider.class);
            when(provider.source()).thenReturn(MediaSource.VIXSRC);
            when(provider.search(eq("breaking bad"), any())).thenReturn(List.of(
                    ContentMetadata.builder().title("Breaking Bad").build()
            ));
            when(sourceRegistry.all()).thenReturn(List.of(provider));

            mvc.perform(get("/api/search").param("query", "breaking bad"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].title").value("Breaking Bad"));
        }

        @Test
        @DisplayName("returns 400 when source param is unknown")
        void searchUnknownSourceReturns400() throws Exception {
            mvc.perform(get("/api/search")
                            .param("query", "test")
                            .param("source", "UNKNOWN_SOURCE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns empty list when provider throws")
        void searchProviderFailsReturnsEmptyList() throws Exception {
            MediaSourceProvider provider = mock(MediaSourceProvider.class);
            when(provider.source()).thenReturn(MediaSource.VIXSRC);
            when(provider.search(any(), any())).thenThrow(new RuntimeException("network error"));
            when(sourceRegistry.all()).thenReturn(List.of(provider));

            mvc.perform(get("/api/search").param("query", "test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("filters by specific known source")
        void searchFilterBySource() throws Exception {
            MediaSourceProvider provider = mock(MediaSourceProvider.class);
            when(provider.source()).thenReturn(MediaSource.VIXSRC);
            when(provider.search(any(), any())).thenReturn(List.of());
            when(sourceRegistry.get(MediaSource.VIXSRC)).thenReturn(provider);

            mvc.perform(get("/api/search")
                            .param("query", "test")
                            .param("source", "VIXSRC"))
                    .andExpect(status().isOk());

            verify(sourceRegistry).get(MediaSource.VIXSRC);
            verify(sourceRegistry, never()).all();
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/search/movies  &  /api/search/tv  (legacy)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Legacy search endpoints")
    class LegacySearchTests {

        @Test
        @DisplayName("GET /api/search/movies returns 200")
        void searchMovies() throws Exception {
            when(metadataService.searchMovies("inception")).thenReturn(List.of(
                    ContentMetadata.builder().title("Inception").build()
            ));

            mvc.perform(get("/api/search/movies").param("query", "inception"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].title").value("Inception"));
        }

        @Test
        @DisplayName("GET /api/search/tv returns 200")
        void searchTv() throws Exception {
            when(metadataService.searchTvShows("lost")).thenReturn(List.of(
                    ContentMetadata.builder().title("Lost").build()
            ));

            mvc.perform(get("/api/search/tv").param("query", "lost"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].title").value("Lost"));
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/download/movie
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/download/movie")
    class DownloadMovieTests {

        @Test
        @DisplayName("returns 200 with the created task")
        void downloadMovieSuccess() throws Exception {
            when(downloadQueueService.addDownload(eq(550), eq(DownloadTask.ContentType.MOVIE),
                    isNull(), isNull(), any(), isNull()))
                    .thenReturn(sampleTask);

            mvc.perform(post("/api/download/movie").param("tmdbId", "550"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("test-uuid"));
        }

        @Test
        @DisplayName("returns 400 when tmdbId is missing")
        void downloadMovieMissingTmdbId() throws Exception {
            mvc.perform(post("/api/download/movie"))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/download/tv
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/download/tv")
    class DownloadTvTests {

        @Test
        @DisplayName("returns 200 with the created task")
        void downloadTvSuccess() throws Exception {
            when(downloadQueueService.addDownload(eq(1396), eq(DownloadTask.ContentType.TV),
                    eq(1), eq(1), any(), isNull()))
                    .thenReturn(sampleTask);

            mvc.perform(post("/api/download/tv")
                            .param("tmdbId", "1396")
                            .param("season", "1")
                            .param("episode", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("test-uuid"));
        }

        @Test
        @DisplayName("returns 400 when season is missing")
        void downloadTvMissingSeason() throws Exception {
            mvc.perform(post("/api/download/tv")
                            .param("tmdbId", "1396")
                            .param("episode", "1"))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/download/raiplay/movie
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/download/raiplay/movie")
    class RaiPlayMovieTests {

        @Test
        @DisplayName("returns 200 with the created task")
        void raiPlayMovieSuccess() throws Exception {
            when(downloadQueueService.addDownload(any(ContentMetadata.class),
                    eq(DownloadTask.ContentType.MOVIE), (Integer) isNull(), (Integer) isNull(),
                    anyList(), (String) isNull()))
                    .thenReturn(sampleTask);

            mvc.perform(post("/api/download/raiplay/movie")
                            .param("pathId", "/video/2018/12/TEST.json")
                            .param("title", "Test Film"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("test-uuid"));
        }

        @Test
        @DisplayName("returns 400 when service returns null (no episodes found)")
        void raiPlayMovieNullTask() throws Exception {
            when(downloadQueueService.addDownload(any(ContentMetadata.class),
                    any(DownloadTask.ContentType.class), (Integer) any(), (Integer) any(),
                    anyList(), (String) any()))
                    .thenReturn(null);

            mvc.perform(post("/api/download/raiplay/movie")
                            .param("pathId", "/video/empty.json")
                            .param("title", "Empty"))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/downloads
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/downloads")
    class GetAllDownloadsTests {

        @Test
        @DisplayName("returns 200 with list of tasks")
        void getAllTasks() throws Exception {
            when(downloadQueueService.getAllTasks()).thenReturn(List.of(sampleTask));

            mvc.perform(get("/api/downloads"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("test-uuid"));
        }

        @Test
        @DisplayName("returns 200 with empty list when no tasks")
        void getAllTasksEmpty() throws Exception {
            when(downloadQueueService.getAllTasks()).thenReturn(List.of());

            mvc.perform(get("/api/downloads"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/downloads/{id}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/downloads/{id}")
    class GetDownloadByIdTests {

        @Test
        @DisplayName("returns 200 when task exists")
        void getExistingTask() throws Exception {
            when(downloadQueueService.getTask("test-uuid")).thenReturn(Optional.of(sampleTask));

            mvc.perform(get("/api/downloads/test-uuid"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("test-uuid"));
        }

        @Test
        @DisplayName("returns 404 when task does not exist")
        void getMissingTask() throws Exception {
            when(downloadQueueService.getTask("missing")).thenReturn(Optional.empty());

            mvc.perform(get("/api/downloads/missing"))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /api/downloads/{id}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /api/downloads/{id}")
    class CancelDownloadTests {

        @Test
        @DisplayName("returns 200 when task is cancelled")
        void cancelExisting() throws Exception {
            when(downloadQueueService.cancelTask("test-uuid")).thenReturn(true);

            mvc.perform(delete("/api/downloads/test-uuid"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 404 when task not found")
        void cancelMissing() throws Exception {
            when(downloadQueueService.cancelTask("missing")).thenReturn(false);

            mvc.perform(delete("/api/downloads/missing"))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/downloads/{id}/retry
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/downloads/{id}/retry")
    class RetryDownloadTests {

        @Test
        @DisplayName("returns 200 when task is re-queued")
        void retryExisting() throws Exception {
            when(downloadQueueService.retryTask("test-uuid")).thenReturn(true);

            mvc.perform(post("/api/downloads/test-uuid/retry"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 404 when task not found")
        void retryMissing() throws Exception {
            when(downloadQueueService.retryTask("missing")).thenReturn(false);

            mvc.perform(post("/api/downloads/missing/retry"))
                    .andExpect(status().isNotFound());
        }
    }
}
