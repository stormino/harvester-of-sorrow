package com.github.stormino.service.link.xdcc;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One XDCC pack as a self-contained link:
 * {@code xdcc://irc.host:6667/%23channel/BotNick/42?net=Network&name=File.mkv&size=123}.
 *
 * @param server   IRC server host
 * @param port     IRC port
 * @param channel  channel the bot sits in (with '#')
 * @param bot      bot nick
 * @param pack     pack number
 * @param network  network name, for display
 * @param fileName file name from the pack list
 * @param size     size in bytes, when known
 */
public record XdccLink(String server, int port, String channel, String bot, int pack, String network,
                       String fileName, Long size) {

    public static final String SCHEME = "xdcc://";

    public String toUrl() {
        StringBuilder b = new StringBuilder(SCHEME).append(server).append(':').append(port).append('/')
                .append(enc(channel)).append('/').append(enc(bot)).append('/').append(pack);
        String sep = "?";
        if (network != null) {
            b.append(sep).append("net=").append(enc(network));
            sep = "&";
        }
        if (fileName != null) {
            b.append(sep).append("name=").append(enc(fileName));
            sep = "&";
        }
        if (size != null) {
            b.append(sep).append("size=").append(size);
        }
        return b.toString();
    }

    public static boolean isXdcc(String url) {
        return url != null && url.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    public static Optional<XdccLink> parse(String url) {
        if (!isXdcc(url)) {
            return Optional.empty();
        }
        try {
            String rest = url.substring(SCHEME.length());
            String query = "";
            int q = rest.indexOf('?');
            if (q >= 0) {
                query = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
            String[] parts = rest.split("/");
            if (parts.length != 4) {
                return Optional.empty();
            }
            String hostPort = parts[0];
            int colon = hostPort.lastIndexOf(':');
            String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
            int port = colon > 0 ? Integer.parseInt(hostPort.substring(colon + 1)) : 6667;
            String channel = dec(parts[1]);
            String bot = dec(parts[2]);
            int pack = Integer.parseInt(parts[3].replace("#", ""));
            Map<String, String> params = new LinkedHashMap<>();
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    params.put(kv.substring(0, eq), dec(kv.substring(eq + 1)));
                }
            }
            if (host.isBlank() || !channel.startsWith("#") || bot.isBlank() || pack <= 0) {
                return Optional.empty();
            }
            Long size = params.containsKey("size") ? Long.valueOf(params.get("size")) : null;
            return Optional.of(new XdccLink(host, port, channel, bot, pack, params.get("net"), params.get("name"), size));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
