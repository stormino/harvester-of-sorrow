package com.github.stormino.service.link;

import com.github.stormino.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct links to a file over HTTP(S): any URL that answers with a file rather than a web page.
 * Downloads resume with Range requests from the {@code .part} file an earlier attempt left.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpLinkProvider implements LinkProvider {

    public static final String ID = "http";
    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0 Safari/537.36";
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("/(\\d+)\\s*$");
    private static final int ATTEMPTS = 4;
    private static final long PROGRESS_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private final OkHttpClient client;
    private final int maxConcurrent;
    private final Duration retryBackoff;

    @Autowired
    public HttpLinkProvider(AppProperties properties) {
        this(new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofSeconds(60))
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build(),
                properties.getLink().getHttp().getMaxConcurrent(), Duration.ofSeconds(5));
    }

    HttpLinkProvider(OkHttpClient client, int maxConcurrent, Duration retryBackoff) {
        this.client = client;
        this.maxConcurrent = maxConcurrent;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Direct link";
    }

    @Override
    public int matchScore(String url) {
        return parse(url) != null ? SCORE_GENERIC : SCORE_NONE;
    }

    @Override
    public int maxConcurrent() {
        return maxConcurrent;
    }

    @Override
    public LinkResolution resolve(String url) {
        HttpUrl httpUrl = parse(url);
        if (httpUrl == null) {
            throw new LinkException("Not an http(s) link");
        }
        try (Response head = client.newCall(request(httpUrl).head().build()).execute()) {
            if (head.isSuccessful()) {
                return describe(url, head, contentLength(head));
            }
        } catch (IOException e) {
            log.debug("HEAD {} failed: {}", url, e.getMessage());
        }
        // Some servers refuse HEAD: ask for the first byte instead.
        try (Response get = client.newCall(request(httpUrl).header("Range", "bytes=0-0").build()).execute()) {
            if (!get.isSuccessful()) {
                throw new LinkException("The server answered HTTP " + get.code());
            }
            Long size = get.code() == 206 ? totalFromContentRange(get.header("Content-Range")) : contentLength(get);
            return describe(url, get, size);
        } catch (IOException e) {
            throw new LinkException("Cannot reach the link: " + e.getMessage(), e);
        }
    }

    private LinkResolution describe(String url, Response response, Long size) {
        String type = response.header("Content-Type", "");
        if (type.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new LinkException("This link opens a web page, not a file");
        }
        String name = fileName(response.header("Content-Disposition"), response.request().url());
        return new LinkResolution(ID, url, false, name, List.of(new LinkFile(url, name, size, List.of())), 0);
    }

    @Override
    public Path download(LinkFile file, Path workDir, String targetName, LinkProgress progress, LinkControl control) {
        HttpUrl httpUrl = parse(file.url());
        if (httpUrl == null) {
            throw new LinkException("Not an http(s) link");
        }
        Path target = workDir.resolve(targetName).normalize();
        if (!workDir.toAbsolutePath().normalize().equals(target.toAbsolutePath().getParent())) {
            throw new LinkException("Unsafe file name: " + targetName);
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(workDir);
            if (Files.isRegularFile(target) && !Files.exists(part)
                    && (file.size() == null || Files.size(target) == file.size())) {
                return target;
            }
        } catch (IOException e) {
            throw new LinkException("Cannot prepare " + workDir + ": " + e.getMessage(), e);
        }

        LinkException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (control.isCancelled()) {
                throw new LinkCancelledException("Cancelled");
            }
            try {
                long total = fetch(httpUrl, part, file.size(), progress, control);
                if (total >= 0 && Files.size(part) != total) {
                    throw new LinkException("Incomplete download: " + Files.size(part) + " of " + total + " bytes");
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (LinkCancelledException e) {
                throw e;
            } catch (IOException | LinkException e) {
                last = e instanceof LinkException le ? le : new LinkException(e.getMessage(), e);
                log.warn("Download of {} failed (attempt {}/{}): {}", file.url(), attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) {
                    sleep(retryBackoff.multipliedBy(attempt), control);
                }
            }
        }
        throw last;
    }

    /** Downloads into {@code part}, resuming it when the server supports ranges; returns the total size or -1. */
    private long fetch(HttpUrl url, Path part, Long knownSize, LinkProgress progress, LinkControl control)
            throws IOException {
        long existing = Files.exists(part) ? Files.size(part) : 0;
        if (knownSize != null && existing == knownSize && existing > 0) {
            return knownSize;
        }
        Request.Builder rb = request(url);
        if (existing > 0) {
            rb.header("Range", "bytes=" + existing + "-");
        }
        var call = client.newCall(rb.build());
        control.onCancel(call::cancel);
        try (Response response = call.execute()) {
            if (response.code() == 416 && knownSize != null && existing == knownSize) {
                return knownSize;
            }
            if (!response.isSuccessful()) {
                throw new LinkException("The server answered HTTP " + response.code());
            }
            boolean append = response.code() == 206;
            if (!append) {
                existing = 0; // no range support: start over
            }
            long total;
            if (append) {
                Long t = totalFromContentRange(response.header("Content-Range"));
                total = t != null ? t : knownSize != null ? knownSize : -1;
            } else {
                Long len = contentLength(response);
                total = len != null ? len : knownSize != null ? knownSize : -1;
            }
            ResponseBody body = response.body();
            byte[] buffer = new byte[64 * 1024];
            long done = existing;
            long windowStart = System.nanoTime();
            long windowBytes = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (control.isCancelled()) {
                        throw new LinkCancelledException("Cancelled");
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    windowBytes += n;
                    long now = System.nanoTime();
                    if (now - windowStart >= PROGRESS_INTERVAL_NANOS) {
                        progress.update(done, total, windowBytes * 1e9 / (now - windowStart));
                        windowStart = now;
                        windowBytes = 0;
                    }
                }
            } catch (IOException e) {
                if (control.isCancelled()) {
                    throw new LinkCancelledException("Cancelled");
                }
                throw e;
            }
            progress.update(done, total, 0);
            return total;
        }
    }

    private static Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("User-Agent", USER_AGENT);
    }

    static HttpUrl parse(String url) {
        if (url == null) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(url.strip());
        return parsed != null && (parsed.scheme().equals("http") || parsed.scheme().equals("https")) ? parsed : null;
    }

    /** Name from Content-Disposition, else from the last path segment of the (redirected) URL. */
    static String fileName(String contentDisposition, HttpUrl url) {
        if (contentDisposition != null) {
            Matcher star = FILENAME_STAR.matcher(contentDisposition);
            if (star.find()) {
                String charset = star.group(1).isBlank() ? "UTF-8" : star.group(1).strip();
                try {
                    return clean(URLDecoder.decode(star.group(2).strip(), charset));
                } catch (Exception ignored) {
                    // fall through to the plain filename
                }
            }
            Matcher plain = FILENAME.matcher(contentDisposition);
            if (plain.find()) {
                String v = plain.group(2) != null ? plain.group(2) : plain.group(1).strip();
                if (!v.isBlank()) {
                    return clean(v);
                }
            }
        }
        List<String> segments = url.pathSegments();
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!segments.get(i).isBlank()) {
                return clean(segments.get(i));
            }
        }
        return "download";
    }

    private static String clean(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).strip();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "download" : n;
    }

    private static Long contentLength(Response response) {
        String v = response.header("Content-Length");
        try {
            long len = v == null ? -1 : Long.parseLong(v.strip());
            return len >= 0 ? len : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long totalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return null;
        }
        Matcher m = CONTENT_RANGE_TOTAL.matcher(contentRange);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static void sleep(Duration d, LinkControl control) {
        long until = System.nanoTime() + d.toNanos();
        while (System.nanoTime() < until && !control.isCancelled()) {
            try {
                Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LinkCancelledException("Interrupted");
            }
        }
    }
}
