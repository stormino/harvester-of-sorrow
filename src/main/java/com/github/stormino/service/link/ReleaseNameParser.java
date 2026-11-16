package com.github.stormino.service.link;

import com.github.stormino.model.DownloadTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort guess of title, year and season/episode from a release-style file name such as
 * {@code The.Matrix.1999.1080p.BluRay.x264-GRP.mkv} or {@code Show.Name.S01E02.Pilot.720p.WEB.mkv}.
 * Folder names fill gaps (show title, season) for files inside a shared folder.
 *
 * <p>Only a guess: the user always confirms the TMDB match before anything is queued.
 */
public final class ReleaseNameParser {

    /** Parsed guess; {@code title} may be empty when nothing usable was found. */
    public record Guess(DownloadTask.ContentType type, String title, Integer year, Integer season,
                        Integer episode, String episodeTitle) {
    }

    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{2,4}$");
    /** Archive or second extension left after the first one ("x.mp4.zip", "x.part1.rar"). */
    private static final Pattern INNER_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|m4v|avi|mov|wmv|ts|webm|mpg|mpeg|part\\d+)$");
    private static final Pattern RESOLUTION = Pattern.compile("(?i)\\d{3,4}x\\d{3,4}");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])");
    private static final Pattern LEADING_TAGS = Pattern.compile("^(\\s*[\\[(][^\\])]*[\\])]\\s*)+");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])((?:19|20)\\d{2})(?![0-9])");
    private static final Pattern[] EPISODE_MARKERS = {
            // S01E02, S1E2, S01E02E03, S01 E02
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2})\\s?e(\\d{1,3})(?:e\\d{1,3})*(?![0-9])"),
            // 1x02
            Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])"),
            // Season 1 Episode 2 / Stagione 1 Episodio 2
            Pattern.compile("(?i)(?:season|stagione)\\s*(\\d{1,2})\\s*[-,]?\\s*(?:episode|episodio|ep)\\s*(\\d{1,3})"),
    };
    /** An episode number without a season: E02, Ep 2, Episodio 2, Episode 2. */
    private static final Pattern BARE_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:e|ep|episode|episodio|puntata)\\s?(\\d{1,3})(?![0-9])");
    /** Folder names that are only a season: Season 1, Stagione 01, S01. */
    private static final Pattern SEASON_FOLDER = Pattern.compile(
            "(?i)^(?:season|stagione|serie|s)\\s?(\\d{1,2})$");
    private static final Set<String> NOISE = Set.of(
            "2160p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hd", "fhd", "sd", "hdr", "hdr10", "dv",
            "web", "web-dl", "webdl", "webrip", "web-rip", "bluray", "blu-ray", "brrip", "bdrip", "bdremux", "remux",
            "dvdrip", "dvd", "hdtv", "hdrip", "tvrip", "satrip", "dlmux", "x264", "x265", "h264", "h265", "h.264",
            "h.265", "hevc", "avc", "xvid", "divx", "10bit", "8bit", "aac", "ac3", "eac3", "dts", "dd5", "ddp",
            "ddp5", "atmos", "truehd", "mp3", "flac", "proper", "repack", "extended", "uncut", "unrated", "remastered",
            "ita", "eng", "multi", "sub", "subs", "subbed", "dubbed", "nf", "amzn", "dsnp", "atvp", "hmax", "rai",
            "complete", "completa", "internal", "limited");

    private ReleaseNameParser() {
    }

    public static Guess parse(String fileName, List<String> folders) {
        String base = fileName == null ? "" : fileName.strip();
        base = EXTENSION.matcher(base).replaceFirst("");
        base = INNER_EXTENSION.matcher(base).replaceFirst("");
        base = LEADING_TAGS.matcher(base).replaceFirst("");
        String text = base.replace('_', ' ').replace('.', ' ').replaceAll("\\s+", " ").strip();

        Integer season = null;
        Integer episode = null;
        int markerStart = -1;
        int markerEnd = -1;
        for (Pattern p : EPISODE_MARKERS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                season = Integer.parseInt(m.group(1));
                episode = Integer.parseInt(m.group(2));
                markerStart = m.start();
                markerEnd = m.end();
                break;
            }
        }
        if (episode == null) {
            Matcher m = BARE_EPISODE.matcher(text);
            if (m.find() && seasonFromFolders(folders) != null) {
                episode = Integer.parseInt(m.group(1));
                markerStart = m.start();
                markerEnd = m.end();
            }
        }
        if (episode != null && season == null) {
            season = seasonFromFolders(folders);
        }

        boolean tv = episode != null;
        String head = markerStart >= 0 ? text.substring(0, markerStart) : text;
        TitleAndYear ty = titleAndYear(head);
        String title = ty.title();
        Integer year = ty.year();
        String episodeTitle = null;
        if (tv) {
            episodeTitle = emptyToNull(titleAndYear(text.substring(markerEnd)).title());
            if (title.isEmpty()) {
                title = showTitleFromFolders(folders);
            }
        }
        return new Guess(tv ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE,
                title, year, season, episode, episodeTitle);
    }

    /** Season number from the closest folder that names one ("Season 2", "Stagione 02", "S02"). */
    static Integer seasonFromFolders(List<String> folders) {
        if (folders == null) {
            return null;
        }
        for (int i = folders.size() - 1; i >= 0; i--) {
            Matcher m = SEASON_FOLDER.matcher(folders.get(i).strip());
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
            Integer s = parseFolderName(folders.get(i)).season();
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** Show title from the closest folder that is not just a season folder. */
    private static String showTitleFromFolders(List<String> folders) {
        if (folders == null) {
            return "";
        }
        for (int i = folders.size() - 1; i >= 0; i--) {
            String f = folders.get(i);
            Guess g = parseFolderName(f);
            if (!g.title().isEmpty() && !SEASON_FOLDER.matcher(f.strip()).matches()) {
                return g.title();
            }
        }
        return "";
    }

    /** Guess for a folder name (e.g. the shared folder of a season pack). */
    public static Guess parseFolderName(String folder) {
        String text = folder == null ? "" : folder.replace('_', ' ').replace('.', ' ').strip();
        Matcher season = Pattern.compile("(?i)(?<![a-z0-9])(?:s|season|stagione)\\s?(\\d{1,2})(?![0-9])").matcher(text);
        Integer s = null;
        String head = text;
        if (season.find()) {
            s = Integer.parseInt(season.group(1));
            head = text.substring(0, season.start());
        }
        TitleAndYear ty = titleAndYear(head);
        return new Guess(s != null ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE,
                ty.title(), ty.year(), s, null, null);
    }

    private record TitleAndYear(String title, Integer year) {
    }

    /**
     * Title = words before the first noise token; year = the last 19xx/20xx word among them that is
     * not the first word (so "Blade Runner 2049 (2017)" keeps 2049 in the title).
     */
    private static TitleAndYear titleAndYear(String text) {
        String[] words = text.replaceAll("[\\[\\]{}]", " ").strip().split("\\s+");
        List<String> kept = new ArrayList<>();
        for (String raw : words) {
            if (raw.isEmpty()) {
                continue;
            }
            String w = raw.replaceAll("^[(\\-]+|[)\\-,]+$", "");
            if (isNoise(w.toLowerCase(Locale.ROOT))) {
                break;
            }
            if (raw.startsWith("-") && !kept.isEmpty() && raw.length() > 1) {
                break; // "-GROUP" release tag
            }
            if (!w.isEmpty()) {
                kept.add(w);
            }
        }
        if (kept.size() == 1) {
            // "BigBuckBunny": one CamelCase word is several words (but not "iCarly" or "McQueen").
            String[] parts = CAMEL.split(kept.get(0));
            if (parts.length > 1 && Character.isUpperCase(parts[0].charAt(0))
                    && java.util.Arrays.stream(parts).allMatch(x -> x.length() >= 3)) {
                kept = new ArrayList<>(List.of(parts));
            }
        }
        for (int i = kept.size() - 1; i > 0; i--) {
            Matcher y = YEAR.matcher(kept.get(i));
            if (y.matches()) {
                return new TitleAndYear(String.join(" ", kept.subList(0, i)).strip(), Integer.parseInt(y.group(1)));
            }
        }
        return new TitleAndYear(String.join(" ", kept).strip(), null);
    }

    private static boolean isNoise(String lw) {
        if (NOISE.contains(lw) || RESOLUTION.matcher(lw).matches()) {
            return true;
        }
        String head = lw.split("[-.]")[0];
        return NOISE.contains(head) && !head.isEmpty() && head.length() > 2;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
