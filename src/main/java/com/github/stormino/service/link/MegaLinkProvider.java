package com.github.stormino.service.link;

import com.github.stormino.config.AppProperties;
import io.github.stormino.megatron.LinkInfo;
import io.github.stormino.megatron.Megatron;
import io.github.stormino.megatron.MegatronException;
import io.github.stormino.megatron.download.DownloadCancelledException;
import io.github.stormino.megatron.download.DownloadControl;
import io.github.stormino.megatron.download.DownloadListener;
import io.github.stormino.megatron.download.DownloadResult;
import io.github.stormino.megatron.download.DownloadSettings;
import io.github.stormino.megatron.proxy.ProxyCache;
import io.github.stormino.megatron.proxy.ProxyInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mega.nz links through the megatron library: each file is fetched in chunks over a pool of free
 * proxies, so Mega's per-IP transfer quota does not apply.
 *
 * <p>Resolving a link calls Mega's API directly (it is not quota-limited) and needs no proxies.
 * Downloads need the pool, which takes minutes to fill from scratch and seconds from the cache
 * file, so it is filled ("warmed up") in the background: on the first Mega link, or at startup
 * when Mega was used recently. Tasks queued meanwhile wait (see {@link #status()}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.mega", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MegaLinkProvider implements LinkProvider {

    public static final String ID = "mega";
    private static final Duration STATUS_EVENT_INTERVAL = Duration.ofSeconds(2);

    private final AppProperties.Link.Mega config;
    private final ApplicationEventPublisher events;
    private final Megatron megatron;
    private final ExecutorService warmUpExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mega-warmup");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean warmUpRunning = new AtomicBoolean();

    private volatile String warmUpStage;
    private volatile int warmUpDone;
    private volatile int warmUpTotal;
    private volatile int warmUpAlive;
    private volatile String lastError;
    private volatile Instant lastStatusEvent = Instant.EPOCH;

    public MegaLinkProvider(AppProperties properties, ApplicationEventPublisher events) {
        this.config = properties.getLink().getMega();
        this.events = events;
        Megatron.Builder builder = Megatron.builder()
                .downloadSettings(DownloadSettings.builder()
                        .connectionsPerFile(config.getConnections())
                        .resolveViaProxy(false)
                        .build())
                .validationProgress(this::onValidationProgress);
        if (config.getCacheFile() != null && !config.getCacheFile().isBlank()) {
            builder.cacheFile(Path.of(config.getCacheFile()));
        }
        List<ProxyInfo> own = config.getProxies().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .map(ProxyInfo::parse)
                .toList();
        if (!own.isEmpty()) {
            builder.proxies(own);
            log.info("Mega: using {} configured proxies", own.size());
        }
        this.megatron = builder.build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mega";
    }

    @Override
    public int matchScore(String url) {
        return Megatron.isMegaLink(url) ? SCORE_SPECIFIC : SCORE_NONE;
    }

    @Override
    public LinkResolution resolve(String url) {
        try {
            LinkInfo info = megatron.resolve(url);
            List<LinkFile> files = info.files().stream()
                    .map(e -> new LinkFile(e.url(), e.fileName(), e.size(), e.folders()))
                    .toList();
            return new LinkResolution(ID, url, info.folder(), info.name(), files, info.truncated());
        } catch (MegatronException e) {
            throw new LinkException("Mega: " + e.getMessage(), e);
        }
    }

    @Override
    public Path download(LinkFile file, Path workDir, String targetName, LinkProgress progress, LinkControl control) {
        DownloadControl megaControl = new DownloadControl();
        control.onCancel(megaControl::cancel);
        DownloadListener listener = new DownloadListener() {
            @Override
            public void onProgress(String url, long bytesDone, long totalBytes, double bytesPerSecond) {
                progress.update(bytesDone, totalBytes, bytesPerSecond);
            }
        };
        try {
            DownloadResult result = megatron.download(file.url(), workDir, targetName, listener, megaControl);
            return result.path();
        } catch (DownloadCancelledException e) {
            throw new LinkCancelledException("Cancelled");
        } catch (MegatronException e) {
            if (control.isCancelled()) {
                throw new LinkCancelledException("Cancelled");
            }
            throw new LinkException("Mega: " + e.getMessage(), e);
        }
    }

    @Override
    public ProviderStatus status() {
        if (megatron.isReady()) {
            return ProviderStatus.ready(megatron.pool().proxyCount() + " proxies, "
                    + megatron.pool().size() + " exit IPs");
        }
        if (warmUpRunning.get()) {
            return new ProviderStatus(ProviderStatus.State.WARMING_UP, warmUpMessage(),
                    warmUpTotal > 0 ? warmUpDone : null, warmUpTotal > 0 ? warmUpTotal : null);
        }
        if (lastError != null) {
            return new ProviderStatus(ProviderStatus.State.ERROR, lastError, null, null);
        }
        return new ProviderStatus(ProviderStatus.State.IDLE, "Proxy pool not warmed up yet", null, null);
    }

    @Override
    public void prepare() {
        if (megatron.isReady() || !warmUpRunning.compareAndSet(false, true)) {
            return;
        }
        lastError = null;
        warmUpStage = null;
        warmUpTotal = 0;
        warmUpExecutor.submit(() -> {
            long t0 = System.nanoTime();
            try {
                log.info("Mega: warming up the proxy pool");
                megatron.warmUp();
                log.info("Mega: proxy pool ready in {}s ({} proxies)",
                        Duration.ofNanos(System.nanoTime() - t0).toSeconds(), megatron.pool().proxyCount());
                saveCache();
            } catch (RuntimeException e) {
                lastError = "Proxy warm-up failed: " + e.getMessage();
                log.warn("Mega: {}", lastError, e);
            } finally {
                warmUpRunning.set(false);
            }
            if (megatron.isReady()) {
                events.publishEvent(new LinkProviderReadyEvent(ID));
            } else {
                events.publishEvent(new LinkProviderStatusEvent(ID, status()));
            }
        });
    }

    @Override
    public int maxConcurrent() {
        return config.getMaxConcurrent();
    }

    /** Warms up at startup when the proxy cache shows Mega was used recently. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpIfRecentlyUsed() {
        if (config.getWarmOnStartupHours() <= 0) {
            return;
        }
        if (!config.getProxies().isEmpty()) {
            prepare(); // own proxies: instant
            return;
        }
        Path cache = cachePath();
        try {
            if (cache != null && Files.isRegularFile(cache) && Files.getLastModifiedTime(cache).toInstant()
                    .isAfter(Instant.now().minus(Duration.ofHours(config.getWarmOnStartupHours())))) {
                log.info("Mega: used within {}h, warming up the proxy pool from {}", config.getWarmOnStartupHours(), cache);
                prepare();
            }
        } catch (Exception e) {
            log.debug("Mega: cannot read {}: {}", cache, e.getMessage());
        }
    }

    /** Saves good proxies regularly, so a crash or kill does not lose the pool. */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT30M")
    public void saveCache() {
        Path cache = cachePath();
        if (cache == null || !megatron.isReady()) {
            return;
        }
        try {
            if (cache.getParent() != null) {
                Files.createDirectories(cache.getParent());
            }
            new ProxyCache(cache).save(megatron.pool().exportForCache(0));
        } catch (Exception e) {
            log.warn("Mega: cannot save the proxy cache to {}: {}", cache, e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        warmUpExecutor.shutdownNow();
        megatron.close(); // stops the refresher and saves the cache
    }

    private Path cachePath() {
        return config.getCacheFile() == null || config.getCacheFile().isBlank() ? null : Path.of(config.getCacheFile());
    }

    private void onValidationProgress(String stage, int done, int total, int alive) {
        warmUpStage = stage;
        warmUpDone = done;
        warmUpTotal = total;
        warmUpAlive = alive;
        Instant now = Instant.now();
        if (Duration.between(lastStatusEvent, now).compareTo(STATUS_EVENT_INTERVAL) >= 0 || done == total) {
            lastStatusEvent = now;
            events.publishEvent(new LinkProviderStatusEvent(ID, status()));
        }
    }

    private String warmUpMessage() {
        String stage = warmUpStage;
        if (stage == null) {
            return "Collecting proxies";
        }
        String what = switch (stage) {
            case "alive" -> "Testing proxies";
            case "mega" -> "Testing proxies against Mega";
            case "speed" -> "Measuring proxy speed";
            default -> "Checking proxies (" + stage + ")";
        };
        return what + ": " + warmUpDone + "/" + warmUpTotal + ", " + warmUpAlive + " working";
    }
}
