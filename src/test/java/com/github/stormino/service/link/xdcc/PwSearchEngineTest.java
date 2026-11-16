package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PwSearchEngineTest {

    @TempDir
    Path dir;
    MockWebServer server;
    final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    volatile boolean databaseAllowed = true;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                requests.add(r);
                String path = r.getPath();
                if (path.startsWith("/PWSearch/nets.php")) {
                    return new MockResponse().setBody("FakeNet~irc.fake.it\nOther~irc.other.it:7000");
                }
                if (path.startsWith("/PWSearch/DataBase.db")) {
                    if (!databaseAllowed) {
                        return new MockResponse().setResponseCode(403).setBody("<html>403 Forbidden</html>");
                    }
                    String base = server.url("/lists").toString();
                    return new MockResponse().setBody("[FakeNet]\r\n0=FakeNet*irc.fake.it*\r\n1=#films*" + base
                            + "/films.php*xdcc*\r\n2=#dead*" + base + "/dead.php*xdcc*\r\n");
                }
                if (path.startsWith("/lists/films.php")) {
                    return new MockResponse().setBody("#3 FilmBot 1.4G Some.Movie.2020.mkv\n#4 FilmBot 700M Other.Film.1999.avi\n");
                }
                return new MockResponse().setResponseCode(500);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private PwSearchEngine engine(String version) {
        AppProperties props = new AppProperties();
        props.getLink().getXdcc().setPwBaseUrl(server.url("/").toString());
        props.getLink().getXdcc().setUserAgentVersion(version);
        props.getLink().getXdcc().setDatabaseCacheFile(dir.resolve("pw.db").toString());
        props.getLink().getXdcc().setSearchTimeoutSeconds(3);
        return new PwSearchEngine(props, "5.4.0");
    }

    @Test
    void searchesEveryListWithTheAgreedUserAgent() {
        PwSearchEngine e = engine("");
        var status = e.status();
        assertTrue(status.available());
        assertEquals(2, status.lists());
        var result = e.search("some movie");
        assertEquals(1, result.failedLists(), "the dead list fails, the rest still answers");
        assertEquals(List.of("Other.Film.1999.avi", "Some.Movie.2020.mkv"),
                result.packs().stream().map(PwSearchEngine.Pack::fileName).toList());
        var pack = result.packs().get(1);
        assertEquals("irc.fake.it", pack.link().server());
        assertEquals("#films", pack.link().channel());
        assertEquals(3, pack.link().pack());
        assertTrue(requests.stream().anyMatch(r -> r.getPath().equals("/lists/films.php?m=1&q=some%20movie")),
                () -> requests.stream().map(RecordedRequest::getPath).toList().toString());
        assertTrue(requests.stream().allMatch(r -> r.getHeader("User-Agent").equals(PwSearchEngine.BROWSER_USER_AGENT + " HoS/5.4.0")));
    }

    @Test
    void theVersionCanBeOverridden() {
        assertEquals(PwSearchEngine.BROWSER_USER_AGENT + " HoS/9.9", engine("9.9").userAgent());
    }

    @Test
    void refusedDatabaseIsReportedAndCachedCopyIsUsedLater() {
        databaseAllowed = false;
        var refused = engine("").status();
        assertFalse(refused.available());
        assertTrue(refused.message().contains("not enabled yet"), refused.message());

        databaseAllowed = true;
        assertTrue(engine("").status().available()); // fetches and caches
        databaseAllowed = false;
        var cached = engine("").status();
        assertTrue(cached.available(), "falls back to the cached copy");
    }
}
