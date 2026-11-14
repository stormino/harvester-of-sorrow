package com.github.stormino.api;

import com.github.stormino.api.dto.ClearResultDto;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Downloads API v1")
class DownloadsApiIT extends AbstractApiIT {

    private static final ParameterizedTypeReference<List<TaskDto>> TASKS = new ParameterizedTypeReference<>() {};
    private static final String VIX_MOVIE_REF = "{\\\"type\\\":\\\"VIXSRC\\\",\\\"tmdbId\\\":550,\\\"season\\\":null,\\\"episode\\\":null}";
    private static final String VIX_TV_REF = "{\\\"type\\\":\\\"VIXSRC\\\",\\\"tmdbId\\\":1396,\\\"season\\\":null,\\\"episode\\\":null}";

    private ResponseEntity<List<TaskDto>> post(String body) {
        return rest.exchange("/api/v1/downloads", HttpMethod.POST, json(body), TASKS);
    }

    private ResponseEntity<ProblemDetail> postExpectingProblem(String body) {
        return rest.exchange("/api/v1/downloads", HttpMethod.POST, json(body), ProblemDetail.class);
    }

    private TaskDto queueMovie() {
        ResponseEntity<List<TaskDto>> resp = post("""
                {"source":"VIXSRC","contentType":"MOVIE","sourceRef":"%s","title":"Fight Club","year":1999,
                 "languages":["en"],"quality":"best"}""".formatted(VIX_MOVIE_REF));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).hasSize(1);
        return resp.getBody().get(0);
    }

    @Test
    @DisplayName("GET /downloads is empty on a fresh DB")
    void list_empty() {
        ResponseEntity<List<TaskDto>> resp = rest.exchange("/api/v1/downloads", HttpMethod.GET, null, TASKS);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("POST /downloads queues a movie and returns the task DTO")
    void create_movie() {
        TaskDto task = queueMovie();
        assertThat(task.id()).isNotBlank();
        assertThat(task.contentType()).isEqualTo(DownloadTask.ContentType.MOVIE);
        assertThat(task.title()).isEqualTo("Fight Club");
        assertThat(task.displayName()).isEqualTo("Fight Club (1999)");
        assertThat(task.tmdbId()).isEqualTo(550);
        assertThat(task.languages()).containsExactly("en");
        assertThat(task.createdAt()).isNotNull();
        assertThat(task.subTasks()).isNotNull();

        List<TaskDto> all = rest.exchange("/api/v1/downloads", HttpMethod.GET, null, TASKS).getBody();
        assertThat(all).extracting(TaskDto::id).containsExactly(task.id());
    }

    @Test
    @DisplayName("POST /downloads never leaks internal fields")
    void create_noInternalFields() {
        queueMovie();
        String raw = rest.getForObject("/api/v1/downloads", String.class);
        assertThat(raw).doesNotContain("playlistUrl", "tempFilePath", "renditionUrl", "aggregated");
    }

    @Test
    @DisplayName("POST /downloads with one season and episodes returns one task per episode")
    void create_episodes_returnsAll() {
        ResponseEntity<List<TaskDto>> resp = post("""
                {"source":"VIXSRC","contentType":"TV","sourceRef":"%s","title":"Breaking Bad","year":2008,
                 "seasons":[1],"episodes":[1,2,3]}""".formatted(VIX_TV_REF));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).hasSize(3);
        assertThat(resp.getBody()).extracting(TaskDto::episode).containsExactly(1, 2, 3);
        assertThat(resp.getBody()).allSatisfy(t -> {
            assertThat(t.season()).isEqualTo(1);
            assertThat(t.title()).isEqualTo("Breaking Bad");
        });
    }

    @Test
    @DisplayName("POST /downloads whole show with nothing to expand returns 400 problem")
    void create_wholeShow_nothingAvailable() {
        // A TMDB id that does not exist: nothing to expand whether or not TMDB_API_KEY is set.
        ResponseEntity<ProblemDetail> resp = postExpectingProblem("""
                {"source":"VIXSRC","contentType":"TV","sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":999999999}",
                 "title":"No Such Show"}""");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(resp.getBody().getDetail()).contains("No episodes found");
    }

    @Test
    @DisplayName("POST /downloads validates the request")
    void create_validation() {
        assertThat(postExpectingProblem("""
                {"source":"VIXSRC","contentType":"MOVIE","sourceRef":"not json","title":"X"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postExpectingProblem("""
                {"source":"VIXSRC","contentType":"MOVIE","sourceRef":"%s","title":"X","languages":["xx"]}"""
                .formatted(VIX_MOVIE_REF)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postExpectingProblem("""
                {"source":"VIXSRC","contentType":"MOVIE","sourceRef":"%s","title":"X","quality":"4k"}"""
                .formatted(VIX_MOVIE_REF)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postExpectingProblem("""
                {"source":"VIXSRC","contentType":"TV","sourceRef":"%s","title":"X","seasons":[1,2],"episodes":[1]}"""
                .formatted(VIX_TV_REF)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postExpectingProblem("""
                {"source":"VIXSRC","contentType":"MOVIE","title":"X"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postExpectingProblem("""
                {"source":"RAIPLAY","contentType":"MOVIE","sourceRef":"{}","title":"X"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForObject("/api/v1/downloads", String.class)).isEqualTo("[]");
    }

    @Test
    @DisplayName("GET /downloads/{id} returns the task, 404 problem when unknown")
    void get_foundAndNotFound() {
        TaskDto task = queueMovie();
        assertThat(rest.getForEntity("/api/v1/downloads/" + task.id(), TaskDto.class).getBody().id())
                .isEqualTo(task.id());

        ResponseEntity<ProblemDetail> missing = rest.getForEntity("/api/v1/downloads/ghost", ProblemDetail.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /cancel cancels a task; a second cancel is a 409")
    void cancel_thenConflict() {
        TaskDto task = queueMovie();
        ResponseEntity<TaskDto> cancelled = rest.postForEntity("/api/v1/downloads/" + task.id() + "/cancel", null, TaskDto.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo(DownloadStatus.CANCELLED);
        assertThat(cancelled.getBody().canCancel()).isFalse();
        assertThat(cancelled.getBody().canRetry()).isTrue();

        ResponseEntity<ProblemDetail> again = rest.postForEntity("/api/v1/downloads/" + task.id() + "/cancel", null, ProblemDetail.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(rest.postForEntity("/api/v1/downloads/ghost/cancel", null, ProblemDetail.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /retry re-queues a cancelled task and rejects completed ones with 409")
    void retry() {
        TaskDto task = queueMovie();
        rest.postForEntity("/api/v1/downloads/" + task.id() + "/cancel", null, TaskDto.class);

        ResponseEntity<TaskDto> retried = rest.postForEntity("/api/v1/downloads/" + task.id() + "/retry", null, TaskDto.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().status()).isNotEqualTo(DownloadStatus.CANCELLED);

        downloadQueueService.getTask(task.id()).orElseThrow().setStatus(DownloadStatus.COMPLETED);
        assertThat(rest.postForEntity("/api/v1/downloads/" + task.id() + "/retry", null, ProblemDetail.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.postForEntity("/api/v1/downloads/ghost/retry", null, ProblemDetail.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /downloads?state=finished removes finished tasks only")
    void clearFinished() {
        TaskDto done = queueMovie();
        TaskDto cancelled = queueMovie();
        downloadQueueService.getTask(done.id()).orElseThrow().setStatus(DownloadStatus.COMPLETED);
        rest.postForEntity("/api/v1/downloads/" + cancelled.id() + "/cancel", null, TaskDto.class);
        TaskDto active = queueMovie();

        ResponseEntity<ClearResultDto> resp = rest.exchange("/api/v1/downloads?state=finished",
                HttpMethod.DELETE, null, ClearResultDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().removed()).isEqualTo(2);

        List<TaskDto> remaining = rest.exchange("/api/v1/downloads", HttpMethod.GET, null, TASKS).getBody();
        assertThat(remaining).extracting(TaskDto::id).containsExactly(active.id());

        assertThat(rest.exchange("/api/v1/downloads?state=all", HttpMethod.DELETE, null, ProblemDetail.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
