package com.github.stormino.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Events API v1 (SSE)")
class EventsApiIT extends AbstractApiIT {

    @LocalServerPort
    int port;

    record Event(String name, String data) {}

    @Test
    @DisplayName("snapshot first, then task.created / task.updated / task.removed")
    void lifecycle() throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");

        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> readEvents(resp.body(), events));
        reader.setDaemon(true);
        reader.start();

        Event snapshot = events.poll(5, TimeUnit.SECONDS);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.name()).isEqualTo("snapshot");
        assertThat(snapshot.data()).isEqualTo("[]");

        String created = rest.exchange("/api/v1/downloads", org.springframework.http.HttpMethod.POST, json("""
                {"source":"VIXSRC","contentType":"MOVIE",
                 "sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":550}","title":"Fight Club"}"""), String.class).getBody();
        String id = created.replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        Event createdEvent = next(events, "task.created");
        assertThat(createdEvent.data()).contains(id).contains("\"subTasks\"");

        rest.postForEntity("/api/v1/downloads/" + id + "/cancel", null, String.class);
        Event cancelled = nextMatching(events, "task.updated", "\"CANCELLED\"");
        assertThat(cancelled.data()).contains(id);

        rest.exchange("/api/v1/downloads?state=finished", org.springframework.http.HttpMethod.DELETE, null, String.class);
        Event removed = next(events, "task.removed");
        assertThat(removed.data()).contains(id);
    }

    private static Event next(BlockingQueue<Event> q, String name) throws InterruptedException {
        return nextMatching(q, name, "");
    }

    private static Event nextMatching(BlockingQueue<Event> q, String name, String contains) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Event e = q.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && e.name().equals(name) && e.data().contains(contains)) {
                return e;
            }
        }
        throw new AssertionError("No '" + name + "' event containing " + contains);
    }

    private static void readEvents(java.io.InputStream in, BlockingQueue<Event> out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String name = "message";
            List<String> data = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        out.add(new Event(name, String.join("\n", data)));
                    }
                    name = "message";
                    data.clear();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring(5));
                }
            }
        } catch (Exception ignored) {
            // stream closed
        }
    }
}
