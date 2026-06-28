package com.github.stormino.e2e;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.TmdbMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@Tag("e2e")
@ActiveProfiles("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("VixSrc — real API calls")
class VixSrcE2EIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TmdbMetadataService tmdbMetadataService;

    @Test
    @DisplayName("GET /api/search?source=VIXSRC returns real results for 'inception' (requires TMDB_API_KEY)")
    void search_vixsrc_movies_returnsRealResults() {
        assumeTrue(tmdbMetadataService.isAvailable(),
                "Skipping: TMDB_API_KEY not configured");

        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=inception&source=VIXSRC&type=MOVIES", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        ContentMetadata first = resp.getBody().get(0);
        assertThat(first.getTitle()).isNotBlank();
        assertThat(first.getTmdbId()).isNotNull();
    }

    @Test
    @DisplayName("GET /api/search?source=VIXSRC&type=TV returns real TV results for 'breaking bad' (requires TMDB_API_KEY)")
    void search_vixsrc_tv_returnsRealResults() {
        assumeTrue(tmdbMetadataService.isAvailable(),
                "Skipping: TMDB_API_KEY not configured");

        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=breaking+bad&source=VIXSRC&type=TV", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().get(0).getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/download/movie queues task and VixSrc returns a real response")
    void queueMovie_vixsrc_taskProcessedByRealApi() {
        // tmdbId 550 = Fight Club — should be available on VixSrc
        ResponseEntity<DownloadTask> resp = rest.postForEntity(
                "/api/download/movie?tmdbId=550", null, DownloadTask.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DownloadTask task = resp.getBody();
        assertThat(task).isNotNull();
        assertThat(task.getId()).isNotBlank();
        assertThat(task.getTmdbId()).isEqualTo(550);

        awaitNotQueued(task.getId());
    }

    @Test
    @DisplayName("POST /api/download/tv queues S01E01 of Breaking Bad and VixSrc returns a real response")
    void queueTv_vixsrc_taskProcessedByRealApi() {
        // tmdbId 1396 = Breaking Bad
        ResponseEntity<DownloadTask> resp = rest.postForEntity(
                "/api/download/tv?tmdbId=1396&season=1&episode=1", null, DownloadTask.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DownloadTask task = resp.getBody();
        assertThat(task).isNotNull();
        assertThat(task.getTmdbId()).isEqualTo(1396);
        assertThat(task.getSeason()).isEqualTo(1);
        assertThat(task.getEpisode()).isEqualTo(1);

        awaitNotQueued(task.getId());
    }

    private void awaitNotQueued(String taskId) {
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    DownloadTask t = rest.getForEntity("/api/downloads/" + taskId, DownloadTask.class).getBody();
                    assertThat(t.getStatus()).isNotEqualTo(DownloadStatus.QUEUED);
                });
    }
}
