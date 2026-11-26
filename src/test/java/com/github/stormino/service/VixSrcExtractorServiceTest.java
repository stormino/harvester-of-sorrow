package com.github.stormino.service;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.PlaylistInfo;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VixSrcExtractorServiceTest {

    MockWebServer server;
    VixSrcExtractorService service;
    volatile boolean canPlayFhd;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath();
                if (path.startsWith("/api/tv/1/1/1")) {
                    return new MockResponse().setBody("{\"src\":\"\\/embed\\/77?token=a&expires=1&lang=en\"}");
                }
                if (path.startsWith("/embed/77")) {
                    return new MockResponse().setBody("""
                            <script>
                            window.masterPlaylist = {
                                params: {
                                    'token': 'tok',
                                    'expires': '123',
                                    'asn': '',
                                },
                                url: '%s/playlist/77',
                            }
                            window.canPlayFHD = %s
                            </script>""".formatted(server.url("").toString().replaceAll("/$", ""), canPlayFhd));
                }
                if (path.startsWith("/playlist/77")) {
                    // VixSrc: Full HD (h=1) is refused unless the page allowed it
                    if (path.contains("h=1") && !canPlayFhd) {
                        return new MockResponse().setResponseCode(403).setBody("Forbidden");
                    }
                    return new MockResponse().setBody("#EXTM3U\n");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        AppProperties props = new AppProperties();
        props.getExtractor().setBaseUrl(server.url("").toString().replaceAll("/$", ""));
        service = new VixSrcExtractorService(new OkHttpClient(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void leavesOutFullHdWhenThePageDoesNotAllowIt() {
        canPlayFhd = false;
        Optional<PlaylistInfo> info = service.getTvPlaylist(1, 1, 1, "it");
        assertTrue(info.isPresent());
        assertFalse(info.get().getUrl().contains("h=1"), info.get().getUrl());
        assertTrue(info.get().getUrl().contains("token=tok"));
        assertTrue(info.get().getUrl().contains("lang=it"));
        assertTrue(info.get().isVerified());
    }

    @Test
    void asksForFullHdWhenAllowed() {
        canPlayFhd = true;
        Optional<PlaylistInfo> info = service.getTvPlaylist(1, 1, 1, "it");
        assertTrue(info.isPresent());
        assertTrue(info.get().getUrl().contains("h=1"), info.get().getUrl());
        assertTrue(info.get().isVerified());
    }
}
