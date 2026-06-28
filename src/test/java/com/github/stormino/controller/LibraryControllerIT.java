package com.github.stormino.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.service.MonitoringService;
import com.github.stormino.service.MonitoringService.LibraryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LibraryController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("LibraryController integration tests")
class LibraryControllerIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    MonitoringService monitoringService;

    private MonitoredShow sampleShow;

    @BeforeEach
    void setUp() {
        sampleShow = MonitoredShow.builder()
                .id("show-uuid")
                .title("Breaking Bad")
                .year(2008)
                .source(MediaSource.VIXSRC)
                .directoryName("Breaking.Bad.2008")
                .enabled(true)
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/library
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/library")
    class ScanLibraryTests {

        @Test
        @DisplayName("returns 200 with library entries")
        void scanLibrary() throws Exception {
            LibraryEntry entry = new LibraryEntry("Breaking.Bad.2008", 5, 62, null);
            when(monitoringService.scanLibrary()).thenReturn(List.of(entry));

            mvc.perform(get("/api/library"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].directoryName").value("Breaking.Bad.2008"));
        }

        @Test
        @DisplayName("returns 200 with empty list when library is empty")
        void scanLibraryEmpty() throws Exception {
            when(monitoringService.scanLibrary()).thenReturn(List.of());

            mvc.perform(get("/api/library"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/library/monitored
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/library/monitored")
    class ListMonitoredTests {

        @Test
        @DisplayName("returns 200 with monitored shows")
        void listMonitored() throws Exception {
            when(monitoringService.listAll()).thenReturn(List.of(sampleShow));

            mvc.perform(get("/api/library/monitored"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("show-uuid"))
                    .andExpect(jsonPath("$[0].title").value("Breaking Bad"));
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/library/monitored/{id}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/library/monitored/{id}")
    class GetMonitoredTests {

        @Test
        @DisplayName("returns 200 when show exists")
        void getExistingShow() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));

            mvc.perform(get("/api/library/monitored/show-uuid"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Breaking Bad"));
        }

        @Test
        @DisplayName("returns 404 when show not found")
        void getMissingShow() throws Exception {
            when(monitoringService.findById("missing")).thenReturn(Optional.empty());

            mvc.perform(get("/api/library/monitored/missing"))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/library/monitored
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/library/monitored")
    class AddMonitoredTests {

        @Test
        @DisplayName("returns 200 with the created monitored show")
        void addMonitoredShow() throws Exception {
            when(monitoringService.addMonitoredShow(any(), any(), any(), any(), any(), any()))
                    .thenReturn(sampleShow);

            String body = """
                    {
                      "title": "Breaking Bad",
                      "year": 2008,
                      "tmdbId": 1396,
                      "source": "VIXSRC",
                      "sourceMetadata": null,
                      "directoryName": "Breaking.Bad.2008"
                    }
                    """;

            mvc.perform(post("/api/library/monitored")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("show-uuid"));
        }
    }

    // -------------------------------------------------------------------------
    // PUT /api/library/monitored/{id}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PUT /api/library/monitored/{id}")
    class UpdateMonitoredTests {

        @Test
        @DisplayName("returns 200 when show exists")
        void updateExistingShow() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));

            String body = """
                    {"title": "Breaking Bad", "year": 2008, "tmdbId": 1396, "source": "VIXSRC", "sourceMetadata": null}
                    """;

            mvc.perform(put("/api/library/monitored/show-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            verify(monitoringService).updateSourceConfig(eq("show-uuid"), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 404 when show not found")
        void updateMissingShow() throws Exception {
            when(monitoringService.findById("missing")).thenReturn(Optional.empty());

            String body = """
                    {"title": "X", "year": null, "tmdbId": null, "source": "VIXSRC", "sourceMetadata": null}
                    """;

            mvc.perform(put("/api/library/monitored/missing")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /api/library/monitored/{id}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /api/library/monitored/{id}")
    class RemoveMonitoredTests {

        @Test
        @DisplayName("returns 204 when show is removed")
        void removeExistingShow() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));

            mvc.perform(delete("/api/library/monitored/show-uuid"))
                    .andExpect(status().isNoContent());

            verify(monitoringService).removeMonitoredShow("show-uuid");
        }

        @Test
        @DisplayName("returns 404 when show not found")
        void removeMissingShow() throws Exception {
            when(monitoringService.findById("missing")).thenReturn(Optional.empty());

            mvc.perform(delete("/api/library/monitored/missing"))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/library/monitored/{id}/enable  &  /disable
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Enable/disable monitoring")
    class EnableDisableTests {

        @Test
        @DisplayName("POST /enable returns 200 when show exists")
        void enableExistingShow() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));

            mvc.perform(post("/api/library/monitored/show-uuid/enable"))
                    .andExpect(status().isOk());

            verify(monitoringService).setEnabled("show-uuid", true);
        }

        @Test
        @DisplayName("POST /disable returns 200 when show exists")
        void disableExistingShow() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));

            mvc.perform(post("/api/library/monitored/show-uuid/disable"))
                    .andExpect(status().isOk());

            verify(monitoringService).setEnabled("show-uuid", false);
        }

        @Test
        @DisplayName("POST /enable returns 404 when show not found")
        void enableMissingShow() throws Exception {
            when(monitoringService.findById("missing")).thenReturn(Optional.empty());

            mvc.perform(post("/api/library/monitored/missing/enable"))
                    .andExpect(status().isNotFound());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/library/monitored/{id}/check
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/library/monitored/{id}/check")
    class CheckNowTests {

        @Test
        @DisplayName("returns 200 with enqueued episode count")
        void checkNowSuccess() throws Exception {
            when(monitoringService.findById("show-uuid")).thenReturn(Optional.of(sampleShow));
            when(monitoringService.checkForNewEpisodes(sampleShow)).thenReturn(3);

            mvc.perform(post("/api/library/monitored/show-uuid/check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.newEpisodesEnqueued").value(3));
        }

        @Test
        @DisplayName("returns 404 when show not found")
        void checkNowMissingShow() throws Exception {
            when(monitoringService.findById("missing")).thenReturn(Optional.empty());

            mvc.perform(post("/api/library/monitored/missing/check"))
                    .andExpect(status().isNotFound());
        }
    }
}
