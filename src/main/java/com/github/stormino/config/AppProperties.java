package com.github.stormino.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "hos")
public class AppProperties {

    private Download download = new Download();
    private Tmdb tmdb = new Tmdb();
    private Extractor extractor = new Extractor();
    private Monitoring monitoring = new Monitoring();
    private Watchlist watchlist = new Watchlist();
    private Link link = new Link();

    @Data
    public static class Download {
        @NotBlank
        private String moviesPath = "/downloads/movies";

        @NotBlank
        private String tvShowsPath = "/downloads/tvshows";

        @NotBlank
        private String tempPath = "/downloads/temp";

        @Min(1)
        private int parallelDownloads = 3;

        @Min(1)
        private int segmentConcurrency = 5;

        @NotBlank
        private String defaultQuality = "best";

        @NotBlank
        private String defaultLanguage = "en";

        public List<String> getDefaultLanguageList() {
            return List.of(defaultLanguage);
        }
    }

    @Data
    public static class Tmdb {
        private String apiKey;

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    @Data
    public static class Monitoring {
        private boolean enabled = true;
        /**
         * When the automatic check runs (Spring cron: second minute hour day month weekday),
         * in the server's time zone. Default: 03:00 and 06:00, overnight.
         */
        @NotBlank
        private String cron = "0 0 3,6 * * *";
        /**
         * New episodes without audio in the default language are not downloaded until it
         * shows up (VixSrc often gets the Italian dub a few days after the original).
         * After this many days from the air date, the episode is downloaded anyway.
         */
        @Min(0)
        private int languageWaitDays = 7;
    }

    @Data
    public static class Watchlist {
        /** Whether the daily check of waiting watchlist titles runs. */
        private boolean enabled = true;
        /** When it runs (Spring cron), in the server's time zone. Default: 07:00, after the monitoring check. */
        @NotBlank
        private String cron = "0 0 7 * * *";
    }

    @Data
    public static class Extractor {
        @NotBlank
        private String baseUrl = "https://vixsrc.to";

        @Min(1)
        private int timeoutSeconds = 30;

        @NotBlank
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

        @Min(100)
        private long retryDelayMs = 2000;

        @Min(1)
        private int maxRetries = Integer.MAX_VALUE;

        @Min(1000)
        private long maxRetryDelayMs = 30000;

        @Min(1)
        private int retryBackoffMultiplier = 2;
    }

    /** Link downloads ("download from link"): one section per provider. */
    @Data
    public static class Link {
        private Mega mega = new Mega();
        private Http http = new Http();
        private Xdcc xdcc = new Xdcc();

        @Data
        public static class Mega {
            private boolean enabled = true;
            /** Proxy pool cache: makes warm-ups after a restart take seconds instead of minutes. */
            private String cacheFile = "data/mega-proxy-cache.json";
            /** Range connections per file, each through a different proxy. */
            @Min(1)
            private int connections = 10;
            /** Mega files downloaded at the same time (each already uses {@link #connections}). */
            @Min(1)
            private int maxConcurrent = 1;
            /**
             * Warm the pool up at startup when Mega was used within this many hours (judged by
             * the cache file); 0 disables it. Otherwise it warms up on the first Mega link.
             */
            @Min(0)
            private int warmOnStartupHours = 72;
            /** Own proxies (scheme://[user:pass@]host:port). When set, public lists are not scraped. */
            private List<String> proxies = new java.util.ArrayList<>();
        }

        @Data
        public static class Http {
            private boolean enabled = true;
            @Min(1)
            private int maxConcurrent = 3;
        }

        /** XDCC over IRC, searched through the PW Search Engine pack lists. */
        @Data
        public static class Xdcc {
            private boolean enabled = true;
            /** Also search xdcc.eu (public XDCC search engine). */
            private boolean xdccEuEnabled = true;
            private String xdccEuUrl = "https://www.xdcc.eu/search.php";
            /** Also search the PW Search Engine (off until its authors enable access for HoS). */
            private boolean pwEnabled = false;
            /** PW Search Engine server (networks, channels, pack database). */
            private String pwBaseUrl = "http://www.pw-search-engine.wklm.it:8282";
            /**
             * Version sent as "HoS/<version>" after the browser User-Agent, as agreed with the PW
             * Search Engine authors. Empty: this HoS version.
             */
            private String userAgentVersion = "";
            /** Nick prefix; a random number is appended (HoS-12345). */
            private String nickPrefix = "HoS-";
            /** XDCC files downloaded at the same time (bots usually give 1-2 slots per user). */
            @Min(1)
            private int maxConcurrent = 2;
            /** Seconds to wait for the bot to answer a request (offer or queue notice). */
            @Min(10)
            private int botReplyTimeoutSeconds = 120;
            /** Minutes a request may wait in a bot's queue before giving up. */
            @Min(1)
            private int queueTimeoutMinutes = 360;
            /** Close an IRC connection after this many idle minutes. */
            @Min(1)
            private int idleDisconnectMinutes = 10;
            /** Per list page, when searching. */
            @Min(1)
            private int searchTimeoutSeconds = 10;
            /** Local copy of the pack database, used when the server cannot be reached. */
            private String databaseCacheFile = "data/pw-database.db";
            /** Re-download the pack database after this many hours. */
            @Min(1)
            private int databaseRefreshHours = 24;
        }
    }
}
