package com.github.stormino.controller;

import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Library API — full stack")
class LibraryApiIT extends AbstractApiIT {

    private HttpEntity<String> jsonBody(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<MonitoredShow> addBreakingBad() {
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
        return rest.postForEntity("/api/library/monitored", jsonBody(body), MonitoredShow.class);
    }

    // -------------------------------------------------------------------------
    // Monitored shows CRUD
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/library/monitored returns empty list on a fresh DB")
    void listMonitored_empty() {
        ResponseEntity<List<MonitoredShow>> resp = rest.exchange(
                "/api/library/monitored", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("POST /api/library/monitored creates a monitored show")
    void addMonitored_created() {
        ResponseEntity<MonitoredShow> resp = addBreakingBad();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MonitoredShow show = resp.getBody();
        assertThat(show.getId()).isNotBlank();
        assertThat(show.getTitle()).isEqualTo("Breaking Bad");
        assertThat(show.getYear()).isEqualTo(2008);
        assertThat(show.getSource()).isEqualTo(MediaSource.VIXSRC);
    }

    @Test
    @DisplayName("GET /api/library/monitored/{id} returns the show after creation")
    void getMonitored_found() {
        MonitoredShow created = addBreakingBad().getBody();

        ResponseEntity<MonitoredShow> resp = rest.getForEntity(
                "/api/library/monitored/" + created.getId(), MonitoredShow.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTitle()).isEqualTo("Breaking Bad");
    }

    @Test
    @DisplayName("GET /api/library/monitored/{id} returns 404 for unknown ID")
    void getMonitored_notFound() {
        ResponseEntity<Void> resp = rest.getForEntity(
                "/api/library/monitored/no-such-id", Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/library/monitored returns all created shows")
    void listMonitored_afterInsert() {
        addBreakingBad();

        ResponseEntity<List<MonitoredShow>> resp = rest.exchange(
                "/api/library/monitored", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).getTitle()).isEqualTo("Breaking Bad");
    }

    @Test
    @DisplayName("PUT /api/library/monitored/{id} updates the show")
    void updateMonitored_success() {
        MonitoredShow created = addBreakingBad().getBody();

        String update = """
                {
                  "title": "Breaking Bad Updated",
                  "year": 2008,
                  "tmdbId": 1396,
                  "source": "VIXSRC",
                  "sourceMetadata": null
                }
                """;
        ResponseEntity<Void> put = rest.exchange(
                "/api/library/monitored/" + created.getId(),
                HttpMethod.PUT, jsonBody(update), Void.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        MonitoredShow updated = rest.getForEntity(
                "/api/library/monitored/" + created.getId(), MonitoredShow.class).getBody();
        assertThat(updated.getTitle()).isEqualTo("Breaking Bad Updated");
    }

    @Test
    @DisplayName("PUT /api/library/monitored/{id} returns 404 for unknown ID")
    void updateMonitored_notFound() {
        String update = """
                {"title": "X", "year": null, "tmdbId": null, "source": "VIXSRC", "sourceMetadata": null}
                """;
        ResponseEntity<Void> resp = rest.exchange(
                "/api/library/monitored/ghost",
                HttpMethod.PUT, jsonBody(update), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/library/monitored/{id} removes the show")
    void removeMonitored_success() {
        MonitoredShow created = addBreakingBad().getBody();

        ResponseEntity<Void> del = rest.exchange(
                "/api/library/monitored/" + created.getId(),
                HttpMethod.DELETE, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> get = rest.getForEntity(
                "/api/library/monitored/" + created.getId(), Void.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/library/monitored/{id} returns 404 for unknown ID")
    void removeMonitored_notFound() {
        ResponseEntity<Void> resp = rest.exchange(
                "/api/library/monitored/ghost", HttpMethod.DELETE, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Enable / Disable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/library/monitored/{id}/disable then /enable toggles the flag")
    void enableDisable_toggles() {
        MonitoredShow created = addBreakingBad().getBody();
        String id = created.getId();

        ResponseEntity<Void> disable = rest.postForEntity(
                "/api/library/monitored/" + id + "/disable", null, Void.class);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.OK);

        MonitoredShow disabled = rest.getForEntity(
                "/api/library/monitored/" + id, MonitoredShow.class).getBody();
        assertThat(disabled.isEnabled()).isFalse();

        ResponseEntity<Void> enable = rest.postForEntity(
                "/api/library/monitored/" + id + "/enable", null, Void.class);
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.OK);

        MonitoredShow enabled = rest.getForEntity(
                "/api/library/monitored/" + id, MonitoredShow.class).getBody();
        assertThat(enabled.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("POST /api/library/monitored/{id}/check returns enqueued count")
    void checkNow_returnsCount() {
        MonitoredShow created = addBreakingBad().getBody();

        ResponseEntity<LibraryController.CheckResult> resp = rest.postForEntity(
                "/api/library/monitored/" + created.getId() + "/check",
                null, LibraryController.CheckResult.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // TMDB not configured → no episodes resolved → 0 enqueued
        assertThat(resp.getBody().newEpisodesEnqueued()).isEqualTo(0);
    }

    @Test
    @DisplayName("POST /api/library/monitored/{id}/check returns 404 for unknown ID")
    void checkNow_notFound() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/api/library/monitored/ghost/check", null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
