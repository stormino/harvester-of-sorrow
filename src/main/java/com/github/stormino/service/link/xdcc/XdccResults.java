package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.link.LinkMatchService;
import com.github.stormino.service.link.ReleaseNameParser;
import com.github.stormino.service.link.ReleaseQuality;
import com.github.stormino.service.link.archive.ArchiveFormats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns raw search hits (one per bot) into what a person wants to pick from:
 * <ol>
 *   <li>one release per file, with every bot offering it as mirrors (tried in turn when downloading);</li>
 *   <li>multi-part archives as one release, flagged when parts are missing;</li>
 *   <li>releases grouped by title (from the release names), most relevant title first;</li>
 *   <li>within a title, releases sorted by quality, preferring the default language;</li>
 *   <li>a TMDB poster for the most relevant titles.</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XdccResults {

    private static final int POSTER_GROUPS = 6;

    /** One volume of a multi-part release. */
    public record Part(String fileName, String size, Long sizeBytes, int bots, XdccLink link) {
    }

    /**
     * One file (or multi-part archive) and every bot offering it.
     *
     * @param label    file name, or the archive set name for multi-part archives
     * @param episode  "S01E02" for TV episodes, else {@code null}
     * @param format   file extension, or "rar ×3" for multi-part archives
     * @param link     link with mirrors (first volume, for multi-part archives)
     * @param parts    all volumes of a multi-part archive (each with its mirrors), else empty
     * @param complete false when volumes of the set are missing
     * @param missing  missing volume numbers
     */
    public record Release(String label, String episode, String size, Long sizeBytes, ReleaseQuality quality,
                          String format, int bots, List<String> networks, XdccLink link, List<Part> parts,
                          boolean complete, List<Integer> missing) {
    }

    /**
     * Releases of one title.
     *
     * @param relevance 3: title starts with the search words; 2: contains them in order; 1: contains
     *                  them all; 0: matched elsewhere in the name
     */
    public record TitleGroup(String title, Integer year, DownloadTask.ContentType type, int relevance,
                             List<Release> releases, Integer tmdbId, String tmdbTitle, String posterUrl) {
    }

    private final ObjectProvider<LinkMatchService> matcher;
    private final String preferredLanguage;
    private final Map<String, Optional<ContentMetadata>> posterCache = new ConcurrentHashMap<>();

    public XdccResults(ObjectProvider<LinkMatchService> matcher, AppProperties properties) {
        this.matcher = matcher;
        this.preferredLanguage = languageTag(properties.getDownload().getDefaultLanguage());
    }

    public List<TitleGroup> group(List<XdccHit> hits, String query) {
        List<TitleGroup> groups = groupWithoutPosters(hits, query, preferredLanguage);
        return withPosters(groups);
    }

    static List<TitleGroup> groupWithoutPosters(List<XdccHit> hits, String query, String preferredLanguage) {
        // 1. one entry per file name, bots as mirrors; games, software, music and books dropped
        Map<String, List<XdccHit>> byFile = hits.stream().filter(h -> !notVideo(h.fileName())).collect(Collectors.groupingBy(
                h -> h.fileName().toLowerCase(Locale.ROOT), LinkedHashMap::new, Collectors.toList()));

        // 2. multi-part archives: one release per set
        Map<String, List<List<XdccHit>>> sets = new LinkedHashMap<>();
        List<Release> releases = new ArrayList<>();
        for (List<XdccHit> mirrors : byFile.values()) {
            String name = mirrors.get(0).fileName();
            Optional<ArchiveFormats.Volume> v = ArchiveFormats.volume(name);
            if (v.isPresent() && isMultiPart(name)) {
                sets.computeIfAbsent(v.get().kind() + ":" + v.get().set().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(mirrors);
            } else {
                releases.add(single(mirrors));
            }
        }
        sets.values().forEach(volumes -> releases.add(set(volumes)));

        // 3. group by title, films apart from episodes: "Curious George (2006)" is both a film and a show,
        //    and one loose episode must not turn the film's releases into the show's
        Map<String, List<Release>> byTitle = new LinkedHashMap<>();
        Map<String, ReleaseNameParser.Guess> guesses = new LinkedHashMap<>();
        for (Release r : releases) {
            ReleaseNameParser.Guess g = ReleaseNameParser.parse(r.label(), List.of());
            g = withoutSeasonPack(g);
            String key = normalize(g.title());
            if (key.isEmpty()) {
                key = normalize(r.label());
            }
            key += episodic(r) ? "|tv" : "|film";
            byTitle.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            guesses.putIfAbsent(key, g);
        }

        List<TitleGroup> groups = new ArrayList<>();
        Comparator<Release> best = Comparator
                .comparing((Release r) -> !r.complete())
                .thenComparing(r -> r.quality().lowQuality())
                .thenComparing(r -> !r.quality().languages().contains(preferredLanguage)
                        && !r.quality().languages().contains("MULTI"))
                .thenComparing(r -> r.episode() == null ? "" : r.episode())
                .thenComparing(Comparator.comparingInt((Release r) -> r.quality().score()).reversed())
                .thenComparing(Comparator.comparingInt(Release::bots).reversed());
        for (Map.Entry<String, List<Release>> e : byTitle.entrySet()) {
            ReleaseNameParser.Guess g = guesses.get(e.getKey());
            List<Release> rs = e.getValue().stream().sorted(best).toList();
            Integer year = rs.stream()
                    .map(r -> ReleaseNameParser.parse(r.label(), List.of()).year())
                    .filter(y -> y != null)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            boolean tv = e.getKey().endsWith("|tv");
            String title = g.title().isEmpty() ? e.getValue().get(0).label() : prettyTitle(g.title());
            groups.add(new TitleGroup(title, year, tv ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE,
                    relevance(query, g.title()), rs, null, null, null));
        }
        groups.sort(Comparator.comparingInt(TitleGroup::relevance).reversed()
                .thenComparing(Comparator.comparingInt((TitleGroup t) -> t.releases().stream().mapToInt(Release::bots).sum()).reversed()));
        return groups;
    }

    private List<TitleGroup> withPosters(List<TitleGroup> groups) {
        LinkMatchService m = matcher.getIfAvailable();
        if (m == null || !m.isAvailable()) {
            return groups;
        }
        List<CompletableFuture<TitleGroup>> futures = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            TitleGroup g = groups.get(i);
            if (i >= POSTER_GROUPS || g.relevance() == 0) {
                futures.add(CompletableFuture.completedFuture(g));
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> {
                String key = g.type() + "|" + normalize(g.title()) + "|" + g.year();
                Optional<ContentMetadata> match = posterCache.computeIfAbsent(key, k -> {
                    try {
                        return m.search(g.title(), g.type(), g.year()).stream().findFirst();
                    } catch (RuntimeException e) {
                        log.debug("TMDB lookup for '{}' failed: {}", g.title(), e.getMessage());
                        return Optional.empty();
                    }
                });
                return match.map(c -> new TitleGroup(g.title(), g.year() != null ? g.year() : c.getYear(), g.type(),
                        g.relevance(), g.releases(), c.getTmdbId(), c.getTitle(), c.getPosterUrl())).orElse(g);
            }));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static Release single(List<XdccHit> mirrors) {
        XdccHit first = mirrors.get(0);
        String name = cleanLabel(first.fileName());
        ReleaseNameParser.Guess g = ReleaseNameParser.parse(name, List.of());
        Matcher ext = FILE_EXTENSION.matcher(name);
        return new Release(name, episode(g), first.size(), first.approxBytes(), ReleaseQuality.parse(name),
                ext.find() ? ext.group(1).toLowerCase(Locale.ROOT) : "?", mirrors.size(), networks(mirrors), withMirrors(mirrors), List.of(), true,
                List.of());
    }

    private static Release set(List<List<XdccHit>> volumes) {
        List<List<XdccHit>> sorted = new ArrayList<>(volumes);
        sorted.sort(Comparator.comparingInt(v -> ArchiveFormats.volume(v.get(0).fileName()).orElseThrow().order()));
        List<Part> parts = sorted.stream()
                .map(v -> new Part(v.get(0).fileName(), v.get(0).size(), v.get(0).approxBytes(), v.size(), withMirrors(v)))
                .toList();
        String firstName = parts.get(0).fileName();
        ArchiveFormats.Volume first = ArchiveFormats.volume(firstName).orElseThrow();
        List<Integer> orders = parts.stream()
                .map(p -> ArchiveFormats.volume(p.fileName()).orElseThrow().order()).toList();
        int start = first.kind() == ArchiveFormats.Kind.RAR && !firstName.toLowerCase(Locale.ROOT).matches(".*\\.part\\d+\\.rar")
                ? 0 : 1; // "x.rar" + "x.r00": the .rar comes first (order 0); "x.partN.rar" and ".001" start at 1
        if (firstName.toLowerCase(Locale.ROOT).matches(".*\\.zip$")) {
            start = 0;
        }
        List<Integer> missing = new ArrayList<>();
        int max = orders.get(orders.size() - 1);
        for (int n = start; n <= max; n++) {
            if (!orders.contains(n)) {
                missing.add(n);
            }
        }
        String base = cleanLabel(ArchiveFormats.baseName(firstName));
        boolean anySize = parts.stream().allMatch(p -> p.sizeBytes() != null);
        Long bytes = anySize ? parts.stream().mapToLong(Part::sizeBytes).sum() : null;
        int bots = parts.stream().mapToInt(Part::bots).min().orElse(0);
        List<String> nets = sorted.stream().flatMap(v -> networks(v).stream()).distinct().toList();
        String kind = first.kind() == ArchiveFormats.Kind.RAR ? "rar" : firstName.toLowerCase(Locale.ROOT).contains(".7z") ? "7z" : "zip";
        ReleaseNameParser.Guess g = ReleaseNameParser.parse(base, List.of());
        return new Release(base, episode(g), bytes == null ? null : humanSize(bytes), bytes, ReleaseQuality.parse(base),
                kind + " ×" + parts.size(), bots, nets, parts.get(0).link(), parts, missing.isEmpty(), missing);
    }

    /** Pack names some bots prefix with their network: "[ irc.coreirc.net ] Title 2006 1080p WEB-DL-GRP". */
    private static final Pattern NETWORK_PREFIX = Pattern.compile("^\\s*\\[\\s*irc\\.[^\\]]*\\]\\s*", Pattern.CASE_INSENSITIVE);
    /** A real file extension: short and alphanumeric, so "Title 2006 H 264-GRP" has none. */
    private static final Pattern FILE_EXTENSION = Pattern.compile("\\.([A-Za-z0-9]{2,4})$");
    private static final Pattern NOT_VIDEO_EXTENSION = Pattern.compile(
            "(?i)\\.(?:exe|msi|apk|dmg|pkg|bin|cue|nsp|xci|epub|mobi|azw3|pdf|cbr|cbz|mp3|flac|m4a|m4b|ogg|wav|aac)$");
    /** Scene and repack groups that release games, not films: "Title.REPACK-RELOADED.tar". */
    private static final Pattern GAME_GROUP = Pattern.compile(
            "(?i)(?:-(?:reloaded|codex|skidrow|plaza|flt|cpy|hoodlum|razor1911|prophet|tenoke|rune|empress|doge|darksiders|tinyiso|goldberg|elamigos|dodi)"
                    + "(?:\\.[a-z0-9]{2,6})*$|\\b(?:fitgirl|elamigos|dodi)[ ._-]?repack)");
    /** A season pack or a whole series without an episode number: "Show.S02.1080p", "Show Complete Series". */
    private static final Pattern SEASON_PACK = Pattern.compile(
            "(?i)(?:^|[ ._\\-\\[(])(?:s\\d{1,2}(?:-?s?\\d{1,2})?|season[ ._]?\\d{1,2}|stagione[ ._]?\\d{1,2}|complete[ ._]series|serie[ ._]completa)(?=$|[ ._\\-\\])])");

    static String cleanLabel(String name) {
        String clean = NETWORK_PREFIX.matcher(name).replaceFirst("");
        return clean.isBlank() ? name : clean;
    }

    /** Games, software, music and books share titles with films ("Curious George" the 2006 game). */
    static boolean notVideo(String fileName) {
        String name = fileName == null ? "" : fileName.strip();
        return NOT_VIDEO_EXTENSION.matcher(name).find() || GAME_GROUP.matcher(name).find();
    }

    /** "Show S03" (a season pack's name, no episode number to cut at) → "Show". */
    private static ReleaseNameParser.Guess withoutSeasonPack(ReleaseNameParser.Guess g) {
        Matcher m = SEASON_PACK.matcher(g.title());
        if (g.episode() != null || !m.find() || m.start() == 0) {
            return g;
        }
        return new ReleaseNameParser.Guess(DownloadTask.ContentType.TV, g.title().substring(0, m.start()).strip(), g.year(),
                g.season(), g.episode(), g.episodeTitle());
    }

    private static boolean episodic(Release r) {
        return r.episode() != null || SEASON_PACK.matcher(r.label()).find();
    }

    /** A volume that belongs to a set (x.part1.rar, x.r00, x.7z.001, x.z01), not a lone archive. */
    private static boolean isMultiPart(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.part\\d+\\.rar") || n.matches(".*\\.r\\d\\d") || n.matches(".*\\.(7z|zip)\\.\\d{3}")
                || n.matches(".*\\.z\\d\\d");
    }

    private static XdccLink withMirrors(List<XdccHit> mirrors) {
        return mirrors.get(0).link().withMirrors(mirrors.subList(1, mirrors.size()).stream().map(XdccHit::link).toList());
    }

    private static List<String> networks(List<XdccHit> mirrors) {
        return mirrors.stream().map(XdccHit::network).distinct().toList();
    }

    private static String episode(ReleaseNameParser.Guess g) {
        return g.episode() == null ? null
                : g.season() != null ? String.format("S%02dE%02d", g.season(), g.episode()) : String.format("E%02d", g.episode());
    }

    static int relevance(String query, String title) {
        String q = normalize(query);
        String t = normalize(title);
        if (q.isEmpty() || t.isEmpty()) {
            return 0;
        }
        if (t.startsWith(q)) {
            return 3;
        }
        if ((" " + t + " ").contains(" " + q + " ") || t.contains(q)) {
            return 2;
        }
        for (String w : q.split(" ")) {
            if (!(" " + t + " ").contains(" " + w + " ")) {
                return 0;
            }
        }
        return 1;
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** "Una Battaglia Dopo L Altra" as found; all-lower or all-upper names get capitalised words. */
    private static String prettyTitle(String t) {
        if (!t.equals(t.toLowerCase(Locale.ROOT)) && !t.equals(t.toUpperCase(Locale.ROOT))) {
            return t;
        }
        return java.util.Arrays.stream(t.toLowerCase(Locale.ROOT).split(" "))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    static String humanSize(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        return g >= 1 ? String.format(Locale.ROOT, "%.1fG", g) : String.format(Locale.ROOT, "%dM", bytes / (1024 * 1024));
    }

    static String languageTag(String code) {
        return switch (code == null ? "" : code.toLowerCase(Locale.ROOT)) {
            case "it" -> "ITA";
            case "en" -> "ENG";
            case "fr" -> "FRE";
            case "de" -> "GER";
            case "es" -> "SPA";
            case "ja" -> "JAP";
            default -> code == null ? "" : code.toUpperCase(Locale.ROOT);
        };
    }
}
