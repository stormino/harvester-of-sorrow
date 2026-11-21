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

class XdccEuSearchTest {

    /** Same structure as xdcc.eu's results table. */
    static String row(String network, String server, String channel, String bot, int pack, String size, String file) {
        return "<tr><td>" + network + "</td><td>" + channel + "<br/><a href=\"irc://" + server + "/" + channel.substring(1)
                + "\" rel=\"nofollow\"><img src=\"x\"></a> <a href=\"#\" onclick=\"ibox(this);return false;\" data-s=\""
                + server + "\" data-c=\"" + channel + "\" data-p=\"" + bot + " xdcc send #" + pack
                + "\"><img src=\"i\"></a></td><td>" + bot + "</td><td>#" + pack + "</td><td>4x</td><td>" + size
                + "</td><td><span class=\"hit\">Dune</span>." + file + "</td></tr>";
    }

    static String page(String... rows) {
        return "<html><body><table id=\"table\" class=\"pinakaki\"><thead><tr><th>Network</th></tr></thead><tbody>"
                + String.join("", rows) + "</tbody></table></body></html>";
    }

    @TempDir
    Path dir;
    MockWebServer server;
    final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                requests.add(r);
                if (r.getPath().startsWith("/search.php")) {
                    return new MockResponse().setBody(page(
                            row("WilliamGattone", "irc.williamgattone.it", "#Film", "Bot|1", 12, "1.4G", "2021.iTA.1080p.mkv"),
                            row("Rizon", "irc.rizon.net:6669", "#news", "News", 3, "700M", "Part.Two.2024.mkv")));
                }
                if (r.getPath().startsWith("/PWSearch/nets.php")) {
                    return new MockResponse().setBody("WilliamGattone~irc.williamgattone.it");
                }
                if (r.getPath().startsWith("/PWSearch/DataBase.db")) {
                    return new MockResponse().setResponseCode(403);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private XdccSearch search() {
        return search(true);
    }

    private XdccSearch search(boolean pwEnabled) {
        AppProperties props = new AppProperties();
        var x = props.getLink().getXdcc();
        x.setPwEnabled(pwEnabled);
        x.setXdccEuUrl(server.url("/search.php").toString());
        x.setPwBaseUrl(server.url("/").toString());
        x.setDatabaseCacheFile(dir.resolve("pw.db").toString());
        x.setSearchTimeoutSeconds(3);
        PwSearchEngine pw = new PwSearchEngine(props, "5.4.0");
        return new XdccSearch(pw, new XdccEuSearch(props, pw));
    }

    @Test
    void parsesTheResultsTable() {
        List<XdccHit> hits = XdccEuSearch.parse(page(
                row("WilliamGattone", "irc.williamgattone.it", "#Film", "Bot|1", 12, "1.4G", "2021.iTA.1080p.mkv"),
                "<tr><td>broken</td></tr>"));
        assertEquals(1, hits.size());
        XdccHit h = hits.get(0);
        assertEquals("Dune.2021.iTA.1080p.mkv", h.fileName());
        assertEquals("Bot|1", h.bot());
        assertEquals(12, h.pack());
        assertEquals("1.4G", h.size());
        assertEquals(new XdccLink("irc.williamgattone.it", 6667, "#Film", "Bot|1", 12, "WilliamGattone",
                "Dune.2021.iTA.1080p.mkv", h.approxBytes()), h.link());
    }

    @Test
    void searchWorksWithXdccEuWhilePwIsRefused() {
        XdccSearch.Result r = search().search("dune");
        assertEquals(List.of("Dune.2021.iTA.1080p.mkv", "Dune.Part.Two.2024.mkv"),
                r.hits().stream().map(XdccHit::fileName).toList());
        assertEquals(6669, r.hits().get(1).link().port());
        assertTrue(r.hits().stream().allMatch(h -> h.source().equals("xdcc.eu")));
        assertEquals("xdcc.eu", r.sources().get(0).name(), "xdcc.eu is the primary source");
        assertTrue(r.sources().get(0).available());
        assertFalse(r.sources().get(1).available());
        assertTrue(r.sources().get(1).message().contains("not enabled yet"));
        RecordedRequest req = requests.stream().filter(x -> x.getPath().startsWith("/search.php")).findFirst().orElseThrow();
        assertEquals("/search.php?searchkey=dune", req.getPath());
        assertEquals(PwSearchEngine.BROWSER_USER_AGENT + " HoS/5.4.0", req.getHeader("User-Agent"));
    }

    @Test
    void pwIsLeftOutWhenDisabled() {
        XdccSearch s = search(false);
        XdccSearch.Result r = s.search("dune");
        assertEquals(2, r.hits().size());
        assertEquals(List.of("xdcc.eu"), r.sources().stream().map(XdccSearch.SourceStatus::name).toList());
        assertEquals(List.of("xdcc.eu"), s.status().stream().map(XdccSearch.SourceStatus::name).toList());
        assertTrue(requests.stream().allMatch(x -> x.getPath().startsWith("/search.php")), "PW never contacted");
    }
}
