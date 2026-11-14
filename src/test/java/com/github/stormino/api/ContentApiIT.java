package com.github.stormino.api;

import com.github.stormino.api.dto.EpisodeDto;
import com.github.stormino.api.dto.SearchResultDto;
import com.github.stormino.api.dto.SourcesDto;
import com.github.stormino.model.MediaSource;
import com.github.stormino.service.TmdbMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Content API v1 (search, sources, episodes)")
class ContentApiIT extends AbstractApiIT {

    @Autowired
    private TmdbMetadataService tmdb;

    @Test
    @DisplayName("GET /search returns 200 (results depend on TMDB_API_KEY)")
    void search_ok() {
        ResponseEntity<List<SearchResultDto>> resp = rest.exchange("/api/v1/search?query=breaking+bad",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (tmdb.isAvailable()) {
            assertThat(resp.getBody()).isNotEmpty().allSatisfy(r -> {
                assertThat(r.contentType()).isNotNull();
                assertThat(r.sourceRef()).isNotBlank();
            });
        } else {
            assertThat(resp.getBody()).isEmpty();
        }
    }

    @Test
    @DisplayName("GET /search validates its parameters")
    void search_validation() {
        assertThat(rest.getForEntity("/api/v1/search?query=x&source=NOPE", ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/search?query=x&type=CARTOONS", ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/search?query={q}", ProblemDetail.class, "  ").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/search", ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/search?query=x&source=RAIPLAY", ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST); // RaiPlay disabled in tests
        assertThat(rest.getForEntity("/api/v1/search?query=x&source=vixsrc&type=movies", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /sources lists VixSrc with its languages and the defaults")
    void sources() {
        SourcesDto sources = rest.getForObject("/api/v1/sources", SourcesDto.class);
        assertThat(sources.sources()).extracting(s -> s.id()).contains(MediaSource.VIXSRC);
        assertThat(sources.sources().get(0).languages()).contains("en", "it");
        assertThat(sources.defaultLanguage()).isEqualTo("en");
        assertThat(sources.defaultQuality()).isEqualTo("best");
        assertThat(sources.qualities()).containsExactly("best", "1080", "720", "worst");
    }

    @Test
    @DisplayName("POST /content/episodes returns an empty list without TMDB and 400 for a bad ref")
    void episodes() {
        ResponseEntity<List<EpisodeDto>> ok = rest.exchange("/api/v1/content/episodes", HttpMethod.POST,
                json("""
                        {"source":"VIXSRC","sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":1396}","season":1}"""),
                new ParameterizedTypeReference<>() {});
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (!tmdb.isAvailable()) {
            assertThat(ok.getBody()).isEmpty();
        }

        assertThat(rest.exchange("/api/v1/content/episodes", HttpMethod.POST,
                json("{\"source\":\"VIXSRC\",\"sourceRef\":\"garbage\"}"), ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/v1/content/episodes", HttpMethod.POST,
                json("{\"source\":\"VIXSRC\"}"), ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
