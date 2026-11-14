package com.github.stormino.api;

import com.github.stormino.api.dto.CheckResultDto;
import com.github.stormino.api.dto.LibraryEntryDto;
import com.github.stormino.api.dto.MonitoredShowDto;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Library API v1")
class LibraryApiIT extends AbstractApiIT {

    private static final String VIX_REF = "{\\\"type\\\":\\\"VIXSRC\\\",\\\"tmdbId\\\":1396,\\\"season\\\":null,\\\"episode\\\":null}";

    @Autowired
    private AppProperties properties;

    private ResponseEntity<MonitoredShowDto> addBreakingBad() {
        return rest.exchange("/api/v1/library/monitored", HttpMethod.POST, json("""
                {"title":"Breaking Bad","year":2008,"source":"VIXSRC","sourceRef":"%s",
                 "directoryName":"Breaking.Bad.Monitored"}""".formatted(VIX_REF)), MonitoredShowDto.class);
    }

    @Test
    @DisplayName("POST /monitored creates, GET returns it, duplicate folder is a 409")
    void create_get_duplicate() {
        ResponseEntity<MonitoredShowDto> created = addBreakingBad();
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MonitoredShowDto show = created.getBody();
        assertThat(show.id()).isNotBlank();
        assertThat(show.tmdbId()).isEqualTo(1396);
        assertThat(show.source()).isEqualTo(MediaSource.VIXSRC);
        assertThat(show.enabled()).isTrue();
        assertThat(show.createdAt()).isNotNull();
        assertThat(show.sourceRef()).contains("1396");

        assertThat(rest.getForObject("/api/v1/library/monitored/" + show.id(), MonitoredShowDto.class).title())
                .isEqualTo("Breaking Bad");
        assertThat(addBreakingBad().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity("/api/v1/library/monitored/ghost", ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /monitored without directoryName is a 400")
    void create_requiresDirectory() {
        ResponseEntity<ProblemDetail> resp = rest.exchange("/api/v1/library/monitored", HttpMethod.POST,
                json("{\"title\":\"X\",\"source\":\"VIXSRC\"}"), ProblemDetail.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PUT /monitored/{id} updates the match")
    void update() {
        MonitoredShowDto show = addBreakingBad().getBody();
        ResponseEntity<MonitoredShowDto> put = rest.exchange("/api/v1/library/monitored/" + show.id(), HttpMethod.PUT,
                json("""
                        {"title":"Breaking Bad (US)","year":2008,"source":"VIXSRC","sourceRef":"%s"}""".formatted(VIX_REF)),
                MonitoredShowDto.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().title()).isEqualTo("Breaking Bad (US)");
        assertThat(put.getBody().directoryName()).isEqualTo("Breaking.Bad.Monitored");

        assertThat(rest.exchange("/api/v1/library/monitored/ghost", HttpMethod.PUT,
                json("{\"title\":\"X\",\"source\":\"VIXSRC\"}"), ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("enable/disable toggle, DELETE removes")
    void toggle_and_delete() {
        MonitoredShowDto show = addBreakingBad().getBody();
        assertThat(rest.postForObject("/api/v1/library/monitored/" + show.id() + "/disable", null, MonitoredShowDto.class)
                .enabled()).isFalse();
        assertThat(rest.postForObject("/api/v1/library/monitored/" + show.id() + "/enable", null, MonitoredShowDto.class)
                .enabled()).isTrue();

        ResponseEntity<Void> del = rest.exchange("/api/v1/library/monitored/" + show.id(), HttpMethod.DELETE, null, Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("/api/v1/library/monitored/" + show.id(), HttpMethod.DELETE, null, ProblemDetail.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /check returns the count (0 when the folder is missing)")
    void check() {
        MonitoredShowDto show = addBreakingBad().getBody();
        ResponseEntity<CheckResultDto> resp = rest.postForEntity(
                "/api/v1/library/monitored/" + show.id() + "/check", null, CheckResultDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().newEpisodesEnqueued()).isZero();
    }

    @Test
    @DisplayName("GET /library merges folders on disk with monitored shows")
    void library_merged() throws Exception {
        Path base = Path.of(properties.getDownload().getTvShowsPath());
        Path season = base.resolve("Some.Show.2020").resolve("Season 01");
        Files.createDirectories(season);
        Files.writeString(season.resolve("Some.Show - S01E01.mp4"), "x");
        try {
            addBreakingBad(); // folder does not exist on disk

            List<LibraryEntryDto> entries = rest.exchange("/api/v1/library", HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<LibraryEntryDto>>() {}).getBody();
            LibraryEntryDto onDisk = entries.stream()
                    .filter(e -> e.directoryName().equals("Some.Show.2020")).findFirst().orElseThrow();
            assertThat(onDisk.seasonCount()).isEqualTo(1);
            assertThat(onDisk.episodeCount()).isEqualTo(1);
            assertThat(onDisk.directoryExists()).isTrue();
            assertThat(onDisk.monitored()).isNull();

            LibraryEntryDto gone = entries.stream()
                    .filter(e -> e.directoryName().equals("Breaking.Bad.Monitored")).findFirst().orElseThrow();
            assertThat(gone.directoryExists()).isFalse();
            assertThat(gone.monitored()).isNotNull();
        } finally {
            org.springframework.util.FileSystemUtils.deleteRecursively(base.resolve("Some.Show.2020"));
        }
    }
}
