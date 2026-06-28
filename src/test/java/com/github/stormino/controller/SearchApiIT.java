package com.github.stormino.controller;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.service.TmdbMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Search API — full stack")
class SearchApiIT extends AbstractApiIT {

    @Autowired
    private TmdbMetadataService tmdbMetadataService;

    @Test
    @DisplayName("GET /api/search returns 200 (results depend on TMDB_API_KEY being set)")
    void search_returnsOk() {
        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=breaking+bad", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (tmdbMetadataService.isAvailable()) {
            assertThat(resp.getBody()).isNotEmpty();
        } else {
            assertThat(resp.getBody()).isEmpty();
        }
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

}
