package com.github.stormino.e2e;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.model.MediaSource;
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

@Tag("e2e")
@ActiveProfiles("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("RaiPlay — real API calls (requires RAIPLAY_USERNAME + RAIPLAY_PASSWORD env vars)")
class RaiPlayE2EIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MediaSourceRegistry sourceRegistry;

    private void assumeRaiPlayAvailable() {
        boolean registered = sourceRegistry.all().stream()
                .anyMatch(p -> p.source() == MediaSource.RAIPLAY);
        assumeTrue(registered,
                "Skipping: RaiPlay provider not registered — set RAIPLAY_USERNAME and RAIPLAY_PASSWORD");
    }

    @Test
    @DisplayName("GET /api/search?source=RAIPLAY returns real results for 'montalbano'")
    void search_raiplay_returnsRealResults() {
        assumeRaiPlayAvailable();

        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=montalbano&source=RAIPLAY", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().get(0).getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("GET /api/search?source=RAIPLAY&type=TV returns TV results for 'rai'")
    void search_raiplay_tv_returnsRealResults() {
        assumeRaiPlayAvailable();

        ResponseEntity<List<ContentMetadata>> resp = rest.exchange(
                "/api/search?query=rai&source=RAIPLAY&type=TV", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().get(0).getTitle()).isNotBlank();
    }
}
