package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import com.github.stormino.service.link.LinkCancelledException;
import com.github.stormino.service.link.LinkControl;
import com.github.stormino.service.link.LinkException;
import com.github.stormino.service.link.LinkFile;
import com.github.stormino.service.link.LinkProgress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class XdccLinkProviderTest {

    @TempDir
    Path dir;
    FakeIrcNetwork net;
    IrcConnections connections;
    XdccLinkProvider provider;
    final List<String> statuses = new CopyOnWriteArrayList<>();
    final LinkProgress progress = new LinkProgress() {
        @Override
        public void update(long done, long total, double bps) {
        }

        @Override
        public void status(String message) {
            if (message != null) {
                statuses.add(message);
            }
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        net = new FakeIrcNetwork();
        net.file = new byte[700_000];
        new Random(3).nextBytes(net.file);
        AppProperties props = new AppProperties();
        props.getLink().getXdcc().setBotReplyTimeoutSeconds(10);
        props.getLink().getXdcc().setQueueTimeoutMinutes(1);
        connections = new IrcConnections(props, org.mockito.Mockito.mock(IrcIdentityService.class));
        provider = new XdccLinkProvider(connections, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        connections.closeAll();
        net.close();
    }

    private LinkFile pack(int n) {
        XdccLink link = new XdccLink("127.0.0.1", net.port(), "#chan", "Bot", n, "FakeNet", "Movie.2020.mkv", null);
        return provider.resolve(link.toUrl()).files().get(0);
    }

    @Test
    void downloadsAPack() throws Exception {
        Path out = provider.download(pack(42), dir, "Movie (2020).mkv", progress, new LinkControl());
        assertEquals(dir.resolve("Movie (2020).mkv"), out);
        assertArrayEquals(net.file, Files.readAllBytes(out));
        assertFalse(Files.exists(dir.resolve("Movie (2020).mkv.part")));
        assertTrue(net.received.stream().anyMatch(l -> l.equals("PRIVMSG Bot :xdcc send #42")), net.received::toString);
        assertTrue(net.received.stream().anyMatch(l -> l.equals("JOIN #chan")));
        assertTrue(statuses.stream().anyMatch(s -> s.contains("Sending you pack #42")), statuses::toString);
    }

    @Test
    void waitsInTheBotQueueThenDownloads() throws Exception {
        net.onRequest = (n, nick, p) -> {
            n.botNotice("Tutti gli slot sono occupati, sei in coda in posizione 2");
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    n.offer();
                } catch (Exception ignored) {
                    // test
                }
            }).start();
        };
        Path out = provider.download(pack(1), dir, "a.mkv", progress, new LinkControl());
        assertArrayEquals(net.file, Files.readAllBytes(out));
        assertTrue(statuses.stream().anyMatch(s -> s.contains("posizione 2")), statuses::toString);
    }

    @Test
    void refusalsFailWithTheBotsWords() {
        net.onRequest = (n, nick, p) -> n.botNotice("** Invalid Pack Number, Try Again");
        LinkException e = assertThrows(LinkException.class,
                () -> provider.download(pack(999), dir, "a.mkv", progress, new LinkControl()));
        assertEquals("Bot: ** Invalid Pack Number, Try Again", e.getMessage());
    }

    @Test
    void botMustBeInTheChannel() {
        net.botInChannel = false;
        LinkException e = assertThrows(LinkException.class,
                () -> provider.download(pack(1), dir, "a.mkv", progress, new LinkControl()));
        assertTrue(e.getMessage().contains("is not in #chan"), e.getMessage());
    }

    @Test
    void resumesAPartialFile() throws Exception {
        Files.write(dir.resolve("a.mkv.part"), java.util.Arrays.copyOf(net.file, 300_000));
        net.acceptResumeAt = 300_000;
        Path out = provider.download(pack(5), dir, "a.mkv", progress, new LinkControl());
        assertArrayEquals(net.file, Files.readAllBytes(out));
        assertTrue(net.received.stream().anyMatch(l -> l.contains("DCC RESUME file.mkv") && l.endsWith(" 300000\u0001")),
                net.received::toString);
    }

    @Test
    void passiveDccIsRefused() {
        net.onRequest = (n, nick, p) -> n.send(":Bot!b@h PRIVMSG " + nick + " :\u0001DCC SEND f.mkv 0 0 100 7\u0001");
        LinkException e = assertThrows(LinkException.class,
                () -> provider.download(pack(1), dir, "a.mkv", progress, new LinkControl()));
        assertTrue(e.getMessage().contains("passive DCC"), e.getMessage());
    }

    @Test
    void cancellingWhileQueuedLeavesTheQueue() throws Exception {
        net.onRequest = (n, nick, p) -> n.botNotice("You are in queue position 4");
        LinkControl control = new LinkControl();
        new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                // test
            }
            control.cancel();
        }).start();
        assertThrows(LinkCancelledException.class, () -> provider.download(pack(9), dir, "a.mkv", progress, control));
        Thread.sleep(300);
        assertTrue(net.received.stream().anyMatch(l -> l.equals("PRIVMSG Bot :xdcc remove #9")), net.received::toString);
    }

    @Test
    void silentBotsTimeOut() {
        net.onRequest = (n, nick, p) -> { };
        AppProperties props = new AppProperties();
        props.getLink().getXdcc().setBotReplyTimeoutSeconds(2);
        XdccLinkProvider impatient = new XdccLinkProvider(connections, props);
        LinkException e = assertThrows(LinkException.class,
                () -> impatient.download(pack(1), dir, "a.mkv", progress, new LinkControl()));
        assertEquals("Bot Bot did not answer", e.getMessage());
    }

    @Test
    void fallsBackToTheNextBot() throws Exception {
        XdccLink ghost = new XdccLink("127.0.0.1", net.port(), "#chan", "Ghost", 1, "FakeNet", "Movie.2020.mkv", null);
        XdccLink real = new XdccLink("127.0.0.1", net.port(), "#chan", "Bot", 7, "FakeNet", "Movie.2020.mkv", null);
        String url = ghost.withMirrors(List.of(real)).toUrl();
        assertEquals(1, XdccLink.parse(url).orElseThrow().mirrors().size());
        LinkFile file = provider.resolve(url).files().get(0);
        Path out = provider.download(file, dir, "a.mkv", progress, new LinkControl());
        assertArrayEquals(net.file, Files.readAllBytes(out));
        assertTrue(statuses.stream().anyMatch(s -> s.contains("Trying another bot (2 of 2): Bot")), statuses::toString);
    }

    @Test
    void reportsWhenEveryBotFailed() {
        net.onRequest = (n, nick, p) -> n.botNotice("** Invalid Pack Number");
        XdccLink a = new XdccLink("127.0.0.1", net.port(), "#chan", "Ghost", 1, "FakeNet", "M.mkv", null);
        XdccLink b = new XdccLink("127.0.0.1", net.port(), "#chan", "Bot", 2, "FakeNet", "M.mkv", null);
        LinkFile file = provider.resolve(a.withMirrors(List.of(b)).toUrl()).files().get(0);
        LinkException e = assertThrows(LinkException.class,
                () -> provider.download(file, dir, "a.mkv", progress, new LinkControl()));
        assertEquals("Bot: ** Invalid Pack Number (all 2 bots tried)", e.getMessage());
    }
}
