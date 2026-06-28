package com.github.stormino.controller;

import com.github.stormino.model.DownloadTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Download queue API — full stack")
class DownloadQueueApiIT extends AbstractApiIT {

    @Test
    @DisplayName("GET /api/downloads returns empty list on a fresh DB")
    void listDownloads_empty() {
        ResponseEntity<List<DownloadTask>> resp = rest.exchange(
                "/api/downloads", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("POST /api/download/movie queues a task and returns it")
    void queueMovie_createsTask() {
        ResponseEntity<DownloadTask> resp = rest.postForEntity(
                "/api/download/movie?tmdbId=550", null, DownloadTask.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DownloadTask task = resp.getBody();
        assertThat(task).isNotNull();
        assertThat(task.getId()).isNotBlank();
        assertThat(task.getTmdbId()).isEqualTo(550);
    }

    @Test
    @DisplayName("POST /api/download/movie then GET /api/downloads returns the task")
    void queueMovie_thenList() {
        rest.postForEntity("/api/download/movie?tmdbId=550", null, DownloadTask.class);

        ResponseEntity<List<DownloadTask>> list = rest.exchange(
                "/api/downloads", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody().get(0).getTmdbId()).isEqualTo(550);
    }

    @Test
    @DisplayName("GET /api/downloads/{id} returns the task when it exists")
    void getDownload_found() {
        DownloadTask created = rest.postForEntity(
                "/api/download/movie?tmdbId=101", null, DownloadTask.class).getBody();
        assertThat(created).isNotNull();

        ResponseEntity<DownloadTask> resp = rest.getForEntity(
                "/api/downloads/" + created.getId(), DownloadTask.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("GET /api/downloads/{id} returns 404 for unknown ID")
    void getDownload_notFound() {
        ResponseEntity<Void> resp = rest.getForEntity(
                "/api/downloads/no-such-id", Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/downloads/{id} cancels the task")
    void cancelDownload_success() {
        DownloadTask created = rest.postForEntity(
                "/api/download/movie?tmdbId=200", null, DownloadTask.class).getBody();

        ResponseEntity<Void> del = rest.exchange(
                "/api/downloads/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("DELETE /api/downloads/{id} returns 404 for unknown ID")
    void cancelDownload_notFound() {
        ResponseEntity<Void> resp = rest.exchange(
                "/api/downloads/ghost", HttpMethod.DELETE, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/downloads/{id}/retry returns 404 for unknown ID")
    void retryDownload_notFound() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/api/downloads/ghost/retry", null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/download/tv queues a TV episode task")
    void queueTvEpisode_createsTask() {
        ResponseEntity<DownloadTask> resp = rest.postForEntity(
                "/api/download/tv?tmdbId=1396&season=1&episode=1", null, DownloadTask.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DownloadTask task = resp.getBody();
        assertThat(task.getTmdbId()).isEqualTo(1396);
        assertThat(task.getSeason()).isEqualTo(1);
        assertThat(task.getEpisode()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/download/movie returns 400 when tmdbId is missing")
    void queueMovie_missingParam() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/api/download/movie", null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
