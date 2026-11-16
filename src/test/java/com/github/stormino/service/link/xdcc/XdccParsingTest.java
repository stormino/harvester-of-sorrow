package com.github.stormino.service.link.xdcc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XdccParsingTest {

    @Test
    void ircMessages() {
        IrcMessage m = IrcMessage.parse(":Bot!bot@host.it PRIVMSG HoS-123 :\u0001DCC SEND \"My File.mkv\" 3232235777 5000 1234\u0001");
        assertEquals("Bot", m.nick());
        assertEquals("PRIVMSG", m.command());
        assertEquals("DCC SEND \"My File.mkv\" 3232235777 5000 1234", m.ctcp());
        IrcMessage ping = IrcMessage.parse("PING :irc.server.it");
        assertEquals("irc.server.it", ping.text());
        IrcMessage names = IrcMessage.parse(":srv 353 HoS-1 = #chan :@Bot +Voice Plain");
        assertEquals(List.of("HoS-1", "=", "#chan", "@Bot +Voice Plain"), names.params());
        assertEquals("Hello world", IrcMessage.stripFormatting("\u00034,1Hello\u0003 \u0002world\u0002"));
    }

    @Test
    void dccOffers() {
        DccOffer o = DccOffer.parseSend("DCC SEND \"My File.mkv\" 3232235777 5000 1234").orElseThrow();
        assertEquals("My File.mkv", o.fileName());
        assertEquals("192.168.1.1", o.host());
        assertEquals(5000, o.port());
        assertEquals(1234, o.size());
        assertFalse(o.passive());
        assertTrue(DccOffer.parseSend("DCC SEND f.mkv 0 0 99 42").orElseThrow().passive());
        assertEquals("::1", DccOffer.parseSend("DCC SEND f.mkv ::1 1 2").orElseThrow().host());
        DccOffer a = DccOffer.parseAccept("DCC ACCEPT f.mkv 5000 777").orElseThrow();
        assertEquals(5000, a.port());
        assertEquals(777, a.size());
        assertTrue(DccOffer.parseSend("VERSION").isEmpty());
    }

    @Test
    void links() {
        XdccLink l = new XdccLink("irc.example.it", 6667, "#Film&Co", "Bot|1", 42, "Example", "Film 2020.mkv", 123L);
        String url = l.toUrl();
        assertTrue(url.startsWith("xdcc://irc.example.it:6667/%23Film%26Co/Bot%7C1/42?"), url);
        assertEquals(l, XdccLink.parse(url).orElseThrow());
        assertTrue(XdccLink.parse("xdcc://host/%23c/bot/0").isEmpty());
        assertTrue(XdccLink.parse("https://x").isEmpty());
        XdccLink minimal = XdccLink.parse("xdcc://host/%23c/bot/7").orElseThrow();
        assertEquals(6667, minimal.port());
        assertNull(minimal.size());
    }

    @Test
    void packListsAndSizes() {
        var list = new PwSearchEngine.PackList("Net", "irc.net.it", 6667, "#chan", "http://lists/x.php");
        var packs = PwSearchEngine.parsePacks(list, """
                <html><body>
                #12 Bot1 1.4G Some.Movie.2020.iTA.1080p.mkv
                #7 Bot2 [ 350M] Show.S01E01.mkv
                junk line
                </body></html>""");
        assertEquals(2, packs.size());
        assertEquals(12, packs.get(0).pack());
        assertEquals("Bot1", packs.get(0).bot());
        assertEquals("Some.Movie.2020.iTA.1080p.mkv", packs.get(0).fileName());
        assertEquals((long) (1.4 * 1024 * 1024 * 1024), packs.get(0).approxBytes());
        assertEquals(null, PwSearchEngine.approxBytes("?"));
        assertEquals(1024L, PwSearchEngine.approxBytes("1K"));
    }

    @Test
    void database() {
        var lists = PwSearchEngine.parseDatabase("""
                [WilliamGattone]
                0=WilliamGattone*irc.williamgattone.it*Italian network
                1=#ZER0*http://lists.example/zero.php*xdcc*info
                2=#Music*https://lists.example/music.php*xdcc*
                3=#broken*not-a-url*xdcc*
                [OtherNet]
                1=#chan*http://other.example/l.php
                [Unknown]
                1=#x*http://u.example/l.php
                """, java.util.Map.of("othernet", "irc.other.net:7000"));
        assertEquals(3, lists.size());
        assertEquals(new PwSearchEngine.PackList("WilliamGattone", "irc.williamgattone.it", 6667, "#ZER0",
                "http://lists.example/zero.php"), lists.get(0));
        assertEquals(new PwSearchEngine.PackList("OtherNet", "irc.other.net", 7000, "#chan",
                "http://other.example/l.php"), lists.get(2));
    }
}
