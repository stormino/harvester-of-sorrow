package com.github.stormino.service.link;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLinkProviderTest {

    @TempDir
    Path dir;
    MockWebServer server;
    HttpLinkProvider provider;
    byte[] data;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new HttpLinkProvider(new OkHttpClient(), 2, Duration.ZERO);
        data = new byte[300_000];
        new Random(1).nextBytes(data);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** Serves {@link #data} with Range support; {@code cutAfter} truncates the first full response. */
    private void serveFile(String disposition, int cutAfter) {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                MockResponse r = new MockResponse().setHeader("Content-Type", "video/x-matroska");
                if (disposition != null) {
                    r.setHeader("Content-Disposition", disposition);
                }
                String range = request.getHeader("Range");
                if (request.getMethod().equals("HEAD")) {
                    return r.setHeader("Content-Length", data.length);
                }
                int start = 0;
                int end = data.length - 1;
                if (range != null) {
                    String[] se = range.substring(6).split("-");
                    start = Integer.parseInt(se[0]);
                    if (se.length > 1 && !se[1].isEmpty()) {
                        end = Integer.parseInt(se[1]);
                    }
                    r.setResponseCode(206).setHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
                }
                int len = end - start + 1;
                if (calls.incrementAndGet() == 1 && cutAfter > 0 && range == null) {
                    // Promise everything, deliver only cutAfter bytes, then drop the connection.
                    return r.setBody(new Buffer().write(data, 0, cutAfter))
                            .setHeader("Content-Length", data.length)
                            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
                }
                return r.setBody(new Buffer().write(data, start, len));
            }
        });
    }

    @Test
    void resolvesNameAndSizeFromHeaders() {
        serveFile("attachment; filename=\"The.Movie.2020.mkv\"", 0);
        LinkResolution r = provider.resolve(server.url("/dl?id=42").toString());
        assertEquals("The.Movie.2020.mkv", r.name());
        assertEquals(1, r.files().size());
        assertEquals(data.length, r.files().get(0).size());
        assertFalse(r.folder());
    }

    @Test
    void namesFromRfc5987AndUrlPath() {
        assertEquals("Città aperta.mkv", HttpLinkProvider.fileName(
                "attachment; filename=\"fallback.mkv\"; filename*=UTF-8''Citt%C3%A0%20aperta.mkv",
                HttpUrl.get("https://x.test/a")));
        assertEquals("film.mp4", HttpLinkProvider.fileName(null, HttpUrl.get("https://x.test/files/film.mp4?token=1")));
        assertEquals("evil.mkv", HttpLinkProvider.fileName("attachment; filename=\"../../evil.mkv\"",
                HttpUrl.get("https://x.test/a")));
        assertEquals(1234L, HttpLinkProvider.totalFromContentRange("bytes 0-0/1234"));
    }

    @Test
    void rejectsWebPages() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8"));
        LinkException e = assertThrows(LinkException.class, () -> provider.resolve(server.url("/page").toString()));
        assertTrue(e.getMessage().contains("web page"));
    }

    @Test
    void fallsBackToARangedGetWhenHeadIsRefused() {
        server.enqueue(new MockResponse().setResponseCode(405));
        server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/999")
                .setHeader("Content-Type", "application/octet-stream").setBody("x"));
        LinkResolution r = provider.resolve(server.url("/files/show.s01e01.mkv").toString());
        assertEquals("show.s01e01.mkv", r.name());
        assertEquals(999L, r.files().get(0).size());
    }

    @Test
    void downloadsAndResumesAfterADroppedConnection() throws Exception {
        serveFile(null, 100_000);
        String url = server.url("/f/movie.mkv").toString();
        LinkFile file = provider.resolve(url).files().get(0);
        AtomicInteger updates = new AtomicInteger();
        Path out = provider.download(file, dir, "Movie (2020).mkv", (d, t, bps) -> updates.incrementAndGet(),
                new LinkControl());
        assertEquals(dir.resolve("Movie (2020).mkv"), out);
        assertArrayEquals(data, Files.readAllBytes(out));
        assertFalse(Files.exists(dir.resolve("Movie (2020).mkv.part")));
        assertTrue(updates.get() > 0);
        // The retry asked only for the missing bytes.
        List<String> ranges = new java.util.ArrayList<>();
        while (server.getRequestCount() > 0) {
            RecordedRequest req = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
            if (req == null) {
                break;
            }
            if (req.getHeader("Range") != null) {
                ranges.add(req.getHeader("Range"));
            }
        }
        assertTrue(ranges.stream().anyMatch(r -> r.startsWith("bytes=") && !r.equals("bytes=0-")),
                () -> "ranges: " + ranges);
    }

    @Test
    void cancellationKeepsThePartialFile() throws Exception {
        serveFile(null, 0);
        LinkControl control = new LinkControl();
        control.cancel();
        LinkFile file = new LinkFile(server.url("/f/x.mkv").toString(), "x.mkv", (long) data.length, null);
        assertThrows(LinkCancelledException.class,
                () -> provider.download(file, dir, "x.mkv", LinkProgress.NONE, control));
        assertFalse(Files.exists(dir.resolve("x.mkv")));
    }

    @Test
    void refusesNamesThatLeaveTheWorkDir() {
        LinkFile file = new LinkFile(server.url("/f/x.mkv").toString(), "x.mkv", 1L, null);
        assertThrows(LinkException.class,
                () -> provider.download(file, dir, "../x.mkv", LinkProgress.NONE, new LinkControl()));
    }
}
