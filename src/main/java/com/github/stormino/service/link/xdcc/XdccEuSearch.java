package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * xdcc.eu, a public XDCC search engine: one page per search with every hit (network, channel,
 * server, bot, pack, size, file name). Requests carry the same {@code ... HoS/<version>}
 * User-Agent as the PW Search Engine ones.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XdccEuSearch {

    public static final String SOURCE = "xdcc.eu";
    /** data-p="Bot xdcc send #12" */
    private static final Pattern REQUEST = Pattern.compile("(?i)^(\\S+)\\s+xdcc\\s+send\\s+#?(\\d+)");

    private final AppProperties.Link.Xdcc config;
    private final PwSearchEngine pw;
    private final OkHttpClient http;

    public XdccEuSearch(AppProperties properties, PwSearchEngine pw) {
        this.config = properties.getLink().getXdcc();
        this.pw = pw;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getSearchTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getSearchTimeoutSeconds() * 2L))
                .followRedirects(true)
                .build();
    }

    public boolean enabled() {
        return config.isXdccEuEnabled();
    }

    public List<XdccHit> search(String words) throws IOException {
        HttpUrl base = HttpUrl.parse(config.getXdccEuUrl());
        if (base == null) {
            throw new IOException("Bad xdcc.eu URL " + config.getXdccEuUrl());
        }
        HttpUrl url = base.newBuilder().addQueryParameter("searchkey", words).build();
        try (Response r = http.newCall(new Request.Builder().url(url).header("User-Agent", pw.userAgent()).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("xdcc.eu answered HTTP " + r.code());
            }
            return parse(r.body().string());
        }
    }

    /** Rows of the results table: network | channel (+ links with data-s/data-c/data-p) | bot | #pack | gets | size | file. */
    static List<XdccHit> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<XdccHit> out = new ArrayList<>();
        for (Element tr : doc.select("table#table tbody tr")) {
            Elements td = tr.select("> td");
            Element info = tr.selectFirst("a[data-s][data-c][data-p]");
            if (td.size() < 7 || info == null) {
                continue;
            }
            Matcher m = REQUEST.matcher(info.attr("data-p").strip());
            if (!m.find()) {
                continue;
            }
            String bot = m.group(1);
            int pack = Integer.parseInt(m.group(2));
            String network = td.get(0).text().strip();
            String server = info.attr("data-s").strip();
            String channel = info.attr("data-c").strip();
            String size = td.get(5).text().strip();
            String fileName = td.get(6).text().strip();
            if (server.isEmpty() || !channel.startsWith("#") || fileName.isEmpty()) {
                continue;
            }
            int port = 6667;
            int colon = server.lastIndexOf(':');
            if (colon > 0 && server.substring(colon + 1).matches("\\d+")) {
                port = Integer.parseInt(server.substring(colon + 1));
                server = server.substring(0, colon);
            }
            Long bytes = PwSearchEngine.approxBytes(size);
            XdccLink link = new XdccLink(server, port, channel, bot, pack, network, fileName, bytes);
            out.add(new XdccHit(SOURCE, network, channel, bot, pack, size, bytes, fileName, link));
        }
        return out;
    }
}
