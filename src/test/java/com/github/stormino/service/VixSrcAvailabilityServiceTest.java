package com.github.stormino.service;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.AvailabilityResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VixSrcAvailabilityServiceTest {

    static final Set<String> IT = Set.of("it");

    MockWebServer server;
    VixSrcAvailabilityService service;
    final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath();
                paths.add(path);
                return switch (path) {
                    // Italian catalogue: only 100 (show) and 200 (movie)
                    case "/api/list/tv?lang=it" -> json("[{\"tmdb_id\":100,\"imdb_id\":\"tt1\"}]");
                    case "/api/list/movie?lang=it" -> json("[{\"tmdb_id\":200,\"imdb_id\":\"tt2\"}]");
                    // English-only show, off the list, as VixSrc's API answers it
                    case "/api/tv/8459/1/1" -> json(
                            "{\"src\":\"\\/embed\\/610994?token=abc&t=QmVpbmc%3D&lang=en&skin=vixsrc&canPlayFHD=1\"}");
                    case "/api/movie/500" -> new MockResponse().setResponseCode(503);
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getExtractor().setBaseUrl(server.url("").toString().replaceAll("/$", ""));
        service = new VixSrcAvailabilityService(new OkHttpClient(), properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void titleOnTheItalianListIsAvailableWithoutProbing() {
        AvailabilityResult result = service.checkTvAvailability(100, IT);

        assertTrue(result.isAvailable());
        assertEquals(Set.of("it"), result.getAvailableLanguages());
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("/api/tv/")));
    }

    @Test
    void titleOffTheListIsAvailableWhenVixSrcServesIt() {
        AvailabilityResult result = service.checkTvAvailability(8459, IT);

        assertTrue(result.isAvailable());
        assertEquals(Set.of("en"), result.getAvailableLanguages());
    }

    @Test
    void titleVixSrcDoesNotHaveIsNotAvailable() {
        assertFalse(service.checkMovieAvailability(999, IT).isAvailable());
        assertFalse(service.checkTvAvailability(999, IT).isAvailable());
    }

    @Test
    void probeAnswersAreCached() {
        service.checkTvAvailability(8459, IT);
        service.checkTvAvailability(8459, IT);
        service.checkMovieAvailability(999, IT);
        service.checkMovieAvailability(999, IT);

        assertEquals(1, paths.stream().filter("/api/tv/8459/1/1"::equals).count());
        assertEquals(1, paths.stream().filter("/api/movie/999"::equals).count());
    }

    @Test
    void serverErrorIsNotCachedAsUnavailable() {
        assertFalse(service.checkMovieAvailability(500, IT).isAvailable());
        assertFalse(service.checkMovieAvailability(500, IT).isAvailable());

        assertEquals(2, paths.stream().filter("/api/movie/500"::equals).count());
    }
}
