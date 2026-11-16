package com.github.stormino.service.link.archive;

import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.link.ReleaseNameParser;
import com.github.stormino.util.MediaFiles;
import com.github.stormino.util.PathUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which unpacked files go where in the library.
 * <ul>
 *   <li>Movie: the largest video.</li>
 *   <li>TV: every video whose season/episode can be read from its name (inside folders and the
 *       archive name fill gaps); the larger file wins when two map to the same episode.</li>
 *   <li>Subtitles: next to the video they belong to (same base name, or same episode), keeping
 *       their language tag ({@code Movie.2020.ita.srt}).</li>
 *   <li>"Sample" videos are ignored.</li>
 * </ul>
 * Anything left out is reported in {@link Plan#notes()}.
 */
public final class ArchivePlacer {

    /** An unpacked file: path relative to the unpack directory. */
    public record Entry(Path path, long size) {
        String name() {
            return path.getFileName().toString();
        }

        List<String> folders() {
            List<String> f = new ArrayList<>();
            for (int i = 0; i < path.getNameCount() - 1; i++) {
                f.add(path.getName(i).toString());
            }
            return f;
        }
    }

    /** Library destinations; extensions include the dot. */
    public interface Destinations {
        String movie(String extension);

        String episode(int season, int episode, String extension);
    }

    public record Placement(Path source, String destination) {
    }

    /**
     * @param placements files to copy (videos first, then subtitles)
     * @param videos     videos placed
     * @param notes      what was left out, for the user
     */
    public record Plan(List<Placement> placements, int videos, List<String> notes) {
    }

    private static final Pattern SAMPLE = Pattern.compile("(?i)(^|[^a-z])sample([^a-z]|$)");
    /** Language tags commonly found at the end of subtitle names (ISO 639-1/2 and English names). */
    private static final Pattern LANG_TOKEN = Pattern.compile("(?i)^(?:it|ita|en|eng|fr|fre|fra|de|ger|deu|es|spa|"
            + "pt|por|pt-br|ja|jpn|ru|rus|nl|dut|nld|pl|pol|zh|chi|zho|sv|swe|da|dan|no|nor|fi|fin|ar|ara|ko|kor|"
            + "forced|sdh|italian|english|french|german|spanish|portuguese|japanese|russian|dutch|polish)$");

    private ArchivePlacer() {
    }

    public static Plan plan(List<Entry> files, DownloadTask.ContentType type, String archiveName,
                            Integer fallbackSeason, Integer fallbackEpisode, Destinations dest) {
        List<Entry> videos = files.stream().filter(e -> MediaFiles.isVideo(e.name())).toList();
        List<Entry> subs = files.stream().filter(e -> MediaFiles.isSubtitle(e.name())).toList();
        List<String> notes = new ArrayList<>();

        List<Entry> real = videos.stream().filter(v -> !SAMPLE.matcher(baseName(v.name())).find()).toList();
        if (real.isEmpty()) {
            real = videos; // only "samples": better than nothing
        }
        if (real.isEmpty()) {
            return new Plan(List.of(), 0, List.of("The archive contains no video files"));
        }

        Map<Entry, String> placed = new LinkedHashMap<>();
        if (type == DownloadTask.ContentType.MOVIE) {
            Entry main = real.stream().max(Comparator.comparingLong(Entry::size)).orElseThrow();
            placed.put(main, dest.movie(MediaFiles.extension(main.name())));
            real.stream().filter(v -> v != main)
                    .forEach(v -> notes.add(v.name() + " not used (the largest video was placed)"));
        } else {
            Map<String, Entry> byEpisode = new LinkedHashMap<>();
            Map<Entry, int[]> numbers = new LinkedHashMap<>();
            for (Entry v : real.stream().sorted(Comparator.comparing(Entry::path)).toList()) {
                List<String> context = new ArrayList<>();
                if (archiveName != null && !archiveName.isBlank()) {
                    context.add(archiveName);
                }
                context.addAll(v.folders());
                ReleaseNameParser.Guess g = ReleaseNameParser.parse(v.name(), context);
                Integer season = g.season() != null ? g.season() : fallbackSeason;
                Integer episode = g.episode();
                if (episode == null && real.size() == 1) {
                    episode = fallbackEpisode;
                }
                if (season == null || episode == null) {
                    notes.add(v.name() + " not placed: no season/episode number in its name");
                    continue;
                }
                String key = season + "x" + episode;
                Entry other = byEpisode.get(key);
                if (other != null) {
                    Entry keep = other.size() >= v.size() ? other : v;
                    Entry drop = keep == other ? v : other;
                    notes.add(drop.name() + " not placed: S" + pad(season) + "E" + pad(episode)
                            + " is already " + keep.name());
                    byEpisode.put(key, keep);
                    numbers.remove(drop);
                    numbers.put(keep, new int[]{season, episode});
                } else {
                    byEpisode.put(key, v);
                    numbers.put(v, new int[]{season, episode});
                }
            }
            for (Map.Entry<Entry, int[]> e : numbers.entrySet()) {
                placed.put(e.getKey(), dest.episode(e.getValue()[0], e.getValue()[1],
                        MediaFiles.extension(e.getKey().name())));
            }
        }

        List<Placement> placements = new ArrayList<>();
        placed.forEach((v, d) -> placements.add(new Placement(v.path(), d)));
        Set<String> taken = new HashSet<>(placed.values());
        for (Entry sub : subs) {
            Entry video = matchVideo(sub, placed.keySet(), type);
            if (video == null) {
                continue;
            }
            String destVideo = placed.get(video);
            String destBase = destVideo.substring(0, destVideo.length() - MediaFiles.extension(destVideo).length());
            String target = uniqueName(destBase + subtitleSuffix(sub.name(), video.name()), MediaFiles.extension(sub.name()), taken);
            placements.add(new Placement(sub.path(), target));
        }
        return new Plan(List.copyOf(placements), placed.size(), List.copyOf(notes));
    }

    /** The placed video a subtitle belongs to: same base name, same episode, or the only video. */
    private static Entry matchVideo(Entry sub, Set<Entry> videos, DownloadTask.ContentType type) {
        if (videos.isEmpty()) {
            return null;
        }
        String subBase = baseName(sub.name()).toLowerCase(Locale.ROOT);
        for (Entry v : videos) {
            if (subBase.startsWith(baseName(v.name()).toLowerCase(Locale.ROOT))) {
                return v;
            }
        }
        if (type == DownloadTask.ContentType.MOVIE || videos.size() == 1) {
            return videos.iterator().next();
        }
        ReleaseNameParser.Guess s = ReleaseNameParser.parse(sub.name(), sub.folders());
        if (s.episode() == null) {
            return null;
        }
        for (Entry v : videos) {
            ReleaseNameParser.Guess g = ReleaseNameParser.parse(v.name(), v.folders());
            if (s.episode().equals(g.episode()) && (s.season() == null || s.season().equals(g.season()))) {
                return v;
            }
        }
        return null;
    }

    /**
     * Language part of a subtitle name: what follows the video's base name ("Movie.ita.srt" next
     * to "Movie.mkv" gives ".ita"), else a trailing language-like token ("English.srt" gives
     * ".English", "subs_it.srt" gives ".it"), else nothing.
     */
    static String subtitleSuffix(String subName, String videoName) {
        String subBase = baseName(subName);
        String videoBase = baseName(videoName);
        if (subBase.toLowerCase(Locale.ROOT).startsWith(videoBase.toLowerCase(Locale.ROOT))
                && subBase.length() > videoBase.length()) {
            String rest = subBase.substring(videoBase.length()).replaceAll("^[ ._-]+", "");
            return rest.isEmpty() ? "" : "." + PathUtils.sanitizeFilename(rest);
        }
        String[] tokens = subBase.split("[ ._-]+");
        String last = tokens[tokens.length - 1];
        if (LANG_TOKEN.matcher(last).matches()) {
            return "." + last;
        }
        return "";
    }

    private static String uniqueName(String base, String ext, Set<String> taken) {
        String candidate = base + ext;
        for (int i = 2; !taken.add(candidate); i++) {
            candidate = base + "." + i + ext;
        }
        return candidate;
    }

    private static String baseName(String fileName) {
        String ext = PathUtils.getExtension(fileName);
        return fileName.substring(0, fileName.length() - ext.length());
    }

    private static String pad(int n) {
        return String.format("%02d", n);
    }
}
