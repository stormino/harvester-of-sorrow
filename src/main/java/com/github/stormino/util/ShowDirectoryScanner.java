package com.github.stormino.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads which episodes of a show are on disk, whatever produced them.
 *
 * <p>The app itself writes {@code Show/Season NN/<title>.SxxEyy.mp4}, but a
 * library also holds files from other tools: other containers ({@code .mkv},
 * {@code .avi}...), and episodes loose in the show folder instead of a season
 * folder. Video files are therefore taken from the show folder and from each
 * direct subfolder, and identified by the {@code SxxEyy} in their name, so the
 * same episode in two formats counts once.
 */
@Slf4j
public final class ShowDirectoryScanner {

    public static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "mkv", "avi", "m4v", "mov", "ts", "webm", "wmv", "mpg", "mpeg");

    /** {@code S01E02}, {@code s1e2}, {@code S01.E02}; not preceded by a letter or digit. */
    private static final Pattern EPISODE =
            Pattern.compile("(?<![A-Za-z0-9])S(\\d{1,3})[ ._-]?E(\\d{1,4})", Pattern.CASE_INSENSITIVE);

    /** {@code Season 1}, {@code Season.01}, {@code S01}, {@code Stagione 2}. */
    private static final Pattern SEASON_DIR =
            Pattern.compile("^(?:season|stagione|s)[ ._-]*(\\d{1,3})$", Pattern.CASE_INSENSITIVE);

    private ShowDirectoryScanner() {}

    public record EpisodeKey(int season, int episode) implements Comparable<EpisodeKey> {
        private static final Comparator<EpisodeKey> ORDER =
                Comparator.comparingInt(EpisodeKey::season).thenComparingInt(EpisodeKey::episode);

        @Override
        public int compareTo(EpisodeKey o) {
            return ORDER.compare(this, o);
        }
    }

    /**
     * @param episodes  distinct episodes identified by {@code SxxEyy}
     * @param unnumbered video files without {@code SxxEyy} found inside a
     *                   recognisable season folder, keyed by that season
     */
    public record Scan(Set<EpisodeKey> episodes, Set<UnnumberedFile> unnumbered) {
        public static final Scan EMPTY = new Scan(Set.of(), Set.of());

        /** Regular seasons (specials, season 0, are not counted). */
        public int seasonCount() {
            Set<Integer> seasons = new HashSet<>();
            episodes.forEach(e -> seasons.add(e.season()));
            unnumbered.forEach(u -> seasons.add(u.season()));
            seasons.remove(0);
            return seasons.size();
        }

        public int episodeCount() {
            return episodes.size() + unnumbered.size();
        }

        /** Highest numbered episode, ignoring specials. */
        public Optional<EpisodeKey> latest() {
            return episodes.stream().filter(e -> e.season() > 0).max(Comparator.naturalOrder());
        }

        public boolean contains(int season, int episode) {
            return episodes.contains(new EpisodeKey(season, episode));
        }
    }

    public record UnnumberedFile(int season, String name) {}

    public static Scan scan(Path showDir) {
        if (!Files.isDirectory(showDir)) return Scan.EMPTY;
        Set<EpisodeKey> episodes = new TreeSet<>();
        Set<UnnumberedFile> unnumbered = new HashSet<>();
        try (Stream<Path> children = Files.list(showDir)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    Integer dirSeason = seasonOfDir(child.getFileName().toString());
                    try (Stream<Path> files = Files.list(child)) {
                        files.filter(ShowDirectoryScanner::isVideoFile)
                                .forEach(f -> add(f, dirSeason, episodes, unnumbered));
                    } catch (IOException e) {
                        log.debug("Could not list {}: {}", child, e.getMessage());
                    }
                } else if (isVideoFile(child)) {
                    add(child, null, episodes, unnumbered);
                }
            }
        } catch (IOException e) {
            log.debug("Could not list show dir {}: {}", showDir, e.getMessage());
        }
        return new Scan(Collections.unmodifiableSet(episodes), Collections.unmodifiableSet(unnumbered));
    }

    private static void add(Path file, Integer dirSeason, Set<EpisodeKey> episodes, Set<UnnumberedFile> unnumbered) {
        String name = file.getFileName().toString();
        Optional<EpisodeKey> key = parseEpisode(name);
        if (key.isPresent()) {
            episodes.add(key.get());
        } else if (dirSeason != null) {
            unnumbered.add(new UnnumberedFile(dirSeason, name));
        }
    }

    public static Optional<EpisodeKey> parseEpisode(String fileName) {
        Matcher m = EPISODE.matcher(fileName);
        if (!m.find()) return Optional.empty();
        return Optional.of(new EpisodeKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }

    static Integer seasonOfDir(String dirName) {
        Matcher m = SEASON_DIR.matcher(dirName.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    static boolean isVideoFile(Path p) {
        String name = p.getFileName().toString();
        // Skip hidden files, including macOS "._" resource forks common on NAS shares.
        if (name.startsWith(".") || !Files.isRegularFile(p)) return false;
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
