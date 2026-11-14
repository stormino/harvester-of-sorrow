package com.github.stormino.e2e;

import com.github.stormino.api.dto.SearchResultDto;
import com.github.stormino.api.dto.TaskDto;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.github.stormino.model.DownloadStatus;
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
    @DisplayName("GET /api/v1/search?source=VIXSRC&type=MOVIES for 'fight club' returns exactly 1 VixSrc-available result")
    void search_vixsrc_movies_filteredByAvailability() {
        assumeTrue(tmdbMetadataService.isAvailable(),
                "Skipping: TMDB_API_KEY not configured");

        ResponseEntity<List<SearchResultDto>> resp = rest.exchange(
                "/api/v1/search?query=fight+club&source=VIXSRC&type=MOVIES", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        SearchResultDto result = resp.getBody().get(0);
        assertThat(result.title()).isEqualToIgnoringCase("Fight Club");
        assertThat(result.tmdbId()).isEqualTo(550);
        assertThat(result.source()).isEqualTo(com.github.stormino.model.MediaSource.VIXSRC);
    }

    @Test
    @DisplayName("GET /api/v1/search?source=VIXSRC&type=TV returns only VixSrc-available shows for 'breaking bad'")
    void search_vixsrc_tv_filteredByAvailability() {
        assumeTrue(tmdbMetadataService.isAvailable(),
                "Skipping: TMDB_API_KEY not configured");

        ResponseEntity<List<SearchResultDto>> resp = rest.exchange(
                "/api/v1/search?query=breaking+bad&source=VIXSRC&type=TV", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody()).allMatch(c -> c.tmdbId() != null);
        assertThat(resp.getBody()).allMatch(c -> c.source() == com.github.stormino.model.MediaSource.VIXSRC);
    }

    @Test
    @DisplayName("POST /api/v1/downloads (movie) queues task and VixSrc returns a real response")
    void queueMovie_vixsrc_taskProcessedByRealApi() {
        // tmdbId 550 = Fight Club — should be available on VixSrc
        TaskDto task = queue("""
                {"source":"VIXSRC","contentType":"MOVIE","sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":550}",
                 "title":"Fight Club","year":1999}""").get(0);
        assertThat(task.id()).isNotBlank();
        assertThat(task.tmdbId()).isEqualTo(550);

        awaitNotQueued(task.id());
    }

    @Test
    @DisplayName("POST /api/v1/downloads (episode) queues S01E01 of Breaking Bad and VixSrc returns a real response")
    void queueTv_vixsrc_taskProcessedByRealApi() {
        // tmdbId 1396 = Breaking Bad
        TaskDto task = queue("""
                {"source":"VIXSRC","contentType":"TV","sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":1396}",
                 "title":"Breaking Bad","year":2008,"seasons":[1],"episodes":[1]}""").get(0);
        assertThat(task.tmdbId()).isEqualTo(1396);
        assertThat(task.season()).isEqualTo(1);
        assertThat(task.episode()).isEqualTo(1);

        awaitNotQueued(task.id());
    }

    private List<TaskDto> queue(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<List<TaskDto>> resp = rest.exchange("/api/v1/downloads", HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private void awaitNotQueued(String taskId) {
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    TaskDto t = rest.getForEntity("/api/v1/downloads/" + taskId, TaskDto.class).getBody();
                    assertThat(t.status()).isNotEqualTo(DownloadStatus.QUEUED);
                });
    }
}
