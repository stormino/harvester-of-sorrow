package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client of the PW Search Engine (the server behind the PWSearch mIRC addon): networks, the pack
 * database (per network and channel, the URL of that channel's XDCC list page), and searches,
 * which query every list page with {@code ?m=1&q=<words>}.
 *
 * <p>Every request identifies itself as a browser followed by {@code HoS/<version>}, as agreed
 * with the PW Search Engine authors.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PwSearchEngine {

    static final String BROWSER_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final Pattern PACK_LINE = Pattern.compile("^#(\\d{1,7})\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s*$");
    private static final Pattern SIZE = Pattern.compile("(?i)^\\[?\\s*([\\d.,]+)\\s*([KMGT]?)i?B?\\s*]?$");
    private static final int MAX_RESULTS = 1000;
    private static final int PARALLEL_LISTS = 16;

    /** One channel's pack list. */
    public record PackList(String network, String server, int port, String channel, String url) {
    }

    /** One search hit. */
    public record Pack(String network, String channel, String bot, int pack, String size, Long approxBytes,
                       String fileName, XdccLink link) {
    }

    public record SearchResult(List<Pack> packs, int lists, int failedLists) {
    }

    /** What the database looks like right now. */
    public record Status(boolean available, String message, int networks, int lists, Instant updatedAt) {
    }

    private final AppProperties.Link.Xdcc config;
    private final String appVersion;
    private final OkHttpClient http;
    private final Path cacheFile;

    private volatile List<PackList> lists = List.of();
    private volatile Instant loadedAt;
    private volatile String lastError;

    public PwSearchEngine(AppProperties properties, @Value("${app.version:unknown}") String appVersion) {
        this.config = properties.getLink().getXdcc();
        this.appVersion = appVersion;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getSearchTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getSearchTimeoutSeconds()))
                .callTimeout(Duration.ofSeconds(config.getSearchTimeoutSeconds() * 3L))
                .followRedirects(true)
                .build();
        this.cacheFile = Path.of(config.getDatabaseCacheFile());
    }

    /** {@code <browser> HoS/<version>}; the version comes from XDCC_USER_AGENT_VERSION or this build. */
    public String userAgent() {
        String v = config.getUserAgentVersion();
        return BROWSER_USER_AGENT + " HoS/" + (v == null || v.isBlank() ? appVersion : v.strip());
    }

    public synchronized Status status() {
        ensureLoaded();
        int networks = (int) lists.stream().map(PackList::network).distinct().count();
        return new Status(!lists.isEmpty(), lists.isEmpty() ? lastError : null, networks, lists.size(), loadedAt);
    }

    /** Searches every pack list; empty when the database is not available. */
    public SearchResult search(String words) {
        List<PackList> all;
        synchronized (this) {
            ensureLoaded();
            all = lists;
        }
        if (words == null || words.isBlank() || all.isEmpty()) {
            return new SearchResult(List.of(), all.size(), 0);
        }
        AtomicInteger failed = new AtomicInteger();
        List<Pack> found = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_LISTS, Thread.ofVirtual().factory())) {
            List<Future<List<Pack>>> futures = all.stream()
                    .map(l -> pool.submit(() -> {
                        try {
                            return searchList(l, words.strip());
                        } catch (IOException | RuntimeException e) {
                            failed.incrementAndGet();
                            log.debug("PW list {} {} failed: {}", l.network(), l.channel(), e.getMessage());
                            return List.<Pack>of();
                        }
                    }))
                    .toList();
            for (Future<List<Pack>> f : futures) {
                try {
                    found.addAll(f.get(config.getSearchTimeoutSeconds() * 3L, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }
        }
        found.sort(Comparator.comparing(Pack::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Pack::network).thenComparing(Pack::bot).thenComparingInt(Pack::pack));
        return new SearchResult(found.size() > MAX_RESULTS ? found.subList(0, MAX_RESULTS) : found, all.size(),
                failed.get());
    }

    List<Pack> searchList(PackList list, String words) throws IOException {
        HttpUrl base = HttpUrl.parse(list.url());
        if (base == null) {
            throw new IOException("Bad list URL " + list.url());
        }
        HttpUrl url = base.newBuilder().addQueryParameter("m", "1").addQueryParameter("q", words).build();
        try (Response r = http.newCall(new Request.Builder().url(url).header("User-Agent", userAgent()).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code());
            }
            return parsePacks(list, r.body().string());
        }
    }

    static List<Pack> parsePacks(PackList list, String body) {
        List<Pack> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            Matcher m = PACK_LINE.matcher(line.replaceAll("<[^>]+>", "").strip());
            if (!m.matches()) {
                continue;
            }
            int pack = Integer.parseInt(m.group(1));
            String bot = m.group(2);
            String size = m.group(3);
            String name = m.group(4).strip();
            Long bytes = approxBytes(size);
            XdccLink link = new XdccLink(list.server(), list.port(), list.channel(), bot, pack, list.network(), name, bytes);
            out.add(new Pack(list.network(), list.channel(), bot, pack, size, bytes, name, link));
        }
        return out;
    }

    /** "1.4G" / "700M" / "[ 350M]" in bytes (approximate: lists round sizes). */
    static Long approxBytes(String size) {
        Matcher m = SIZE.matcher(size.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            double n = Double.parseDouble(m.group(1).replace(',', '.'));
            int exp = switch (m.group(2).toUpperCase(Locale.ROOT)) {
                case "K" -> 1;
                case "M" -> 2;
                case "G" -> 3;
                case "T" -> 4;
                default -> 0;
            };
            return (long) (n * Math.pow(1024, exp));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Loads (or refreshes) the database: networks from nets.php, lists from DataBase.db. */
    private void ensureLoaded() {
        boolean fresh = loadedAt != null
                && loadedAt.isAfter(Instant.now().minus(Duration.ofHours(config.getDatabaseRefreshHours())));
        if (fresh && !lists.isEmpty()) {
            return;
        }
        if (loadedAt != null && lastError != null
                && loadedAt.isAfter(Instant.now().minus(Duration.ofMinutes(10)))) {
            return; // failed recently: don't hammer the server
        }
        try {
            Map<String, String> servers = networks();
            String db = fetch("/PWSearch/DataBase.db");
            List<PackList> parsed = parseDatabase(db, servers);
            if (parsed.isEmpty()) {
                throw new IOException("the pack database is empty");
            }
            lists = parsed;
            lastError = null;
            saveCache(db);
            log.info("PW Search Engine: {} pack lists on {} networks", parsed.size(),
                    parsed.stream().map(PackList::network).distinct().count());
        } catch (IOException e) {
            lastError = e.getMessage();
            log.warn("PW Search Engine: {}", e.getMessage());
            if (lists.isEmpty()) {
                loadCache();
            }
        }
        loadedAt = Instant.now();
    }

    /** network name (lower case) -> "host" or "host:port", from nets.php. */
    Map<String, String> networks() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : fetch("/PWSearch/nets.php").split("\\R")) {
            int t = line.indexOf('~');
            if (t > 0 && !line.startsWith("HTTP/")) {
                out.put(line.substring(0, t).strip().toLowerCase(Locale.ROOT), line.substring(t + 1).strip());
            }
        }
        return out;
    }

    /**
     * The database is an INI file: one section per network; entry 0 is {@code network*server*...},
     * every other entry {@code #channel*listUrl*type*info}.
     */
    static List<PackList> parseDatabase(String ini, Map<String, String> servers) {
        List<PackList> out = new ArrayList<>();
        String section = null;
        Map<String, String> entries = new LinkedHashMap<>();
        for (String raw : (ini + "\n[]").split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (section != null && !entries.isEmpty()) {
                    addSection(section, entries, servers, out);
                }
                section = line.substring(1, line.length() - 1);
                entries = new LinkedHashMap<>();
            } else if (section != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    entries.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        }
        return out;
    }

    private static void addSection(String section, Map<String, String> entries, Map<String, String> servers,
                                   List<PackList> out) {
        String[] head = entries.getOrDefault("0", "").split("\\*");
        String network = head.length > 0 && !head[0].isBlank() ? head[0].strip() : section;
        String server = head.length > 1 && !head[1].isBlank() ? head[1].strip()
                : servers.get(network.toLowerCase(Locale.ROOT));
        if (server == null) {
            server = servers.get(section.toLowerCase(Locale.ROOT));
        }
        if (server == null) {
            return;
        }
        String host = server;
        int port = 6667;
        int colon = server.lastIndexOf(':');
        if (colon > 0 && server.substring(colon + 1).matches("\\d+")) {
            host = server.substring(0, colon);
            port = Integer.parseInt(server.substring(colon + 1));
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey().equals("0")) {
                continue;
            }
            String[] t = e.getValue().split("\\*");
            if (t.length >= 2 && t[0].startsWith("#") && t[1].matches("(?i)https?://.+")) {
                out.add(new PackList(network, host, port, t[0].strip(), t[1].strip()));
            }
        }
    }

    private String fetch(String path) throws IOException {
        String url = config.getPwBaseUrl().replaceAll("/+$", "") + path;
        try (Response r = http.newCall(new Request.Builder().url(url).header("User-Agent", userAgent()).build()).execute()) {
            if (r.code() == 403) {
                throw new IOException("the PW Search Engine refused access (403): access for HoS is not enabled yet");
            }
            if (!r.isSuccessful()) {
                throw new IOException("PW Search Engine answered HTTP " + r.code() + " for " + path);
            }
            return new String(r.body().bytes(), StandardCharsets.UTF_8);
        }
    }

    private void saveCache(String db) {
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile, db);
        } catch (IOException e) {
            log.debug("Cannot cache the PW database: {}", e.getMessage());
        }
    }

    private void loadCache() {
        try {
            if (Files.isRegularFile(cacheFile)) {
                List<PackList> cached = parseDatabase(Files.readString(cacheFile), networks());
                if (!cached.isEmpty()) {
                    lists = cached;
                    log.info("PW Search Engine: using the cached database ({} lists)", cached.size());
                }
            }
        } catch (IOException e) {
            log.debug("No usable cached PW database: {}", e.getMessage());
        }
    }
}
