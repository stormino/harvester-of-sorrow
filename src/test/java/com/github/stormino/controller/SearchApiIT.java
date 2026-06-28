package com.github.stormino.controller;

import com.github.stormino.model.ContentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Search API — full stack")
class SearchApiIT extends AbstractApiIT {

    @Test
    @DisplayName("GET /api/search returns 200 with empty list when TMDB is not configured")
    void search_noTmdb_returnsEmpty() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=breaking+bad", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("GET /api/search?source=INVALID returns 400")
    void search_invalidSource_returns400() {
        ResponseEntity<Void> resp = rest.exchange(
                "/api/search?query=test&source=INVALID_SOURCE", HttpMethod.GET, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /api/search?source=VIXSRC returns 200")
    void search_validSource_returns200() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=test&source=VIXSRC", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /api/search?type=MOVIES returns 200")
    void search_typeMovies_returns200() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=inception&type=MOVIES", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /api/search/movies returns 200")
    void searchMovies_returns200() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search/movies?query=fight+club", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("GET /api/search/tv returns 200")
    void searchTv_returns200() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search/tv?query=lost", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }
}
