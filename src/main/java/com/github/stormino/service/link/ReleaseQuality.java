package com.github.stormino.service.link;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quality tags read from a release name ({@code Movie.2025.iTALiAN.1080p.WEB-DL.x264.AC3.Sub.Ita-GRP.mkv}).
 *
 * @param resolution  vertical resolution (2160, 1080, 720, 576, 480) or {@code null}
 * @param source      BluRay, Remux, BDRip, WEB-DL, WEBRip, HDTV, DVD9, DVD5, DVDRip, TS, CAM, ... or {@code null}
 * @param codec       x265, x264, AV1, XviD or {@code null}
 * @param hdr         HDR / Dolby Vision
 * @param audio       audio codec (DTS, TrueHD, DDP, AC3, AAC, ...) or {@code null}
 * @param languages   audio languages (ITA, ENG, ...; MULTI when tagged so)
 * @param subtitles   subtitle languages ({@code Sub.Ita} gives ITA)
 * @param lowQuality  camcorder/telesync/line-dubbed releases
 */
public record ReleaseQuality(Integer resolution, String source, String codec, boolean hdr, String audio,
                             List<String> languages, List<String> subtitles, boolean lowQuality) {

    private static final Pattern RES = Pattern.compile("(?i)(?<![0-9])(2160|1080|720|576|480)[pi]?(?![0-9])");
    private static final Pattern UHD = Pattern.compile("(?i)\\b(4k|uhd)\\b");
    private static final Pattern SUBS = Pattern.compile("(?i)\\bsubs?[ ._-]?(ita|eng|en|it|multi)\\b|\\b(ita|eng)[ ._-]?subs?\\b");

    private static final String[][] SOURCES = {
            {"Remux", "remux"}, {"BluRay", "bluray", "blu-ray", "bdmux"}, {"BDRip", "bdrip", "brrip"},
            {"WEB-DL", "web-dl", "webdl", "web"}, {"WEBRip", "webrip", "web-rip", "webmux"},
            {"HDTV", "hdtv", "dlmux", "satrip", "dvb"}, {"DVD9", "dvd9"}, {"DVD5", "dvd5"},
            {"DVDRip", "dvdrip", "dvdmux", "dvd"}, {"HDRip", "hdrip"},
            {"TS", "ts", "telesync", "hdts", "tc", "telecine"}, {"CAM", "cam", "camrip", "hdcam"},
    };
    private static final String[][] CODECS = {
            {"x265", "x265", "h265", "hevc"}, {"x264", "x264", "h264", "avc"}, {"AV1", "av1"},
            {"XviD", "xvid", "divx"},
    };
    private static final String[][] AUDIO = {
            {"TrueHD", "truehd", "atmos"}, {"DTS", "dts", "dts-hd"}, {"DDP", "ddp", "eac3", "dd+"},
            {"AC3", "ac3", "dd"}, {"AAC", "aac"}, {"MP3", "mp3"},
    };
    private static final String[][] LANGS = {
            {"ITA", "ita", "italian", "iTALiAN"}, {"ENG", "eng", "english"}, {"FRE", "fre", "french"},
            {"GER", "ger", "german"}, {"SPA", "spa", "spanish"}, {"JAP", "jap", "jpn", "japanese"},
            {"MULTI", "multi"},
    };

    public static ReleaseQuality parse(String name) {
        // without the extension(s): ".ts" or ".part1.rar" must not read as tags
        String n = (name == null ? "" : name)
                .replaceAll("(?i)(\\.part\\d+)?\\.(rar|r\\d\\d|zip|7z|\\d{3})$", "")
                .replaceAll("(?i)\\.(mkv|mp4|m4v|avi|mov|wmv|ts|m2ts|webm|mpg|mpeg|flv)$", "");
        List<String> tokens = new ArrayList<>();
        for (String t : n.toLowerCase(Locale.ROOT).split("[ ._\\[\\]()+]+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
                // "ITA-ENG", "x264-GROUP": the parts count too
                for (String p : t.split("-")) {
                    if (!p.isEmpty() && !p.equals(t)) {
                        tokens.add(p);
                    }
                }
            }
        }
        Integer resolution = null;
        Matcher r = RES.matcher(n.replaceAll("(?i)\\b(19|20)\\d{2}\\b", " "));
        if (r.find()) {
            resolution = Integer.parseInt(r.group(1));
        } else if (UHD.matcher(n).find()) {
            resolution = 2160;
        }
        String source = first(tokens, SOURCES);
        String codec = first(tokens, CODECS);
        String audio = first(tokens, AUDIO);
        boolean hdr = tokens.stream().anyMatch(t -> t.equals("hdr") || t.equals("hdr10") || t.equals("dv")
                || t.equals("dovi"));

        Set<String> subtitles = new LinkedHashSet<>();
        Matcher s = SUBS.matcher(n.replace('.', ' '));
        String withoutSubs = n;
        while (s.find()) {
            String lang = (s.group(1) != null ? s.group(1) : s.group(2)).toUpperCase(Locale.ROOT);
            subtitles.add(switch (lang) {
                case "IT" -> "ITA";
                case "EN" -> "ENG";
                default -> lang;
            });
        }
        if (!subtitles.isEmpty()) {
            withoutSubs = SUBS.matcher(n.replace('.', ' ')).replaceAll(" ");
        }
        List<String> langTokens = new ArrayList<>();
        for (String t : withoutSubs.toLowerCase(Locale.ROOT).split("[ ._\\[\\]()+-]+")) {
            langTokens.add(t);
        }
        Set<String> languages = new LinkedHashSet<>();
        for (String[] l : LANGS) {
            for (int i = 1; i < l.length; i++) {
                if (langTokens.contains(l[i].toLowerCase(Locale.ROOT))) {
                    languages.add(l[0]);
                    break;
                }
            }
        }
        boolean md = langTokens.contains("md") || langTokens.contains("ld"); // mic-/line-dubbed audio
        boolean low = md || "CAM".equals(source) || "TS".equals(source);
        return new ReleaseQuality(resolution, source, codec, hdr, audio, List.copyOf(languages),
                List.copyOf(subtitles), low);
    }

    /** Higher is better: resolution, then source, then codec; low-quality releases sink. */
    public int score() {
        int s = resolution == null ? 300 : resolution >= 2160 ? 900 : resolution >= 1080 ? 700 : resolution >= 720 ? 500 : 300;
        s += switch (source == null ? "" : source) {
            case "Remux" -> 90;
            case "BluRay" -> 80;
            case "WEB-DL" -> 70;
            case "BDRip" -> 60;
            case "WEBRip" -> 55;
            case "HDTV" -> 40;
            case "DVD9" -> 35;
            case "DVD5" -> 30;
            case "DVDRip", "HDRip" -> 20;
            default -> 0;
        };
        s += "x265".equals(codec) || "AV1".equals(codec) ? 8 : "x264".equals(codec) ? 5 : 0;
        s += hdr ? 3 : 0;
        return lowQuality ? s - 1000 : s;
    }

    private static String first(List<String> tokens, String[][] table) {
        for (String[] row : table) {
            for (int i = 1; i < row.length; i++) {
                if (tokens.contains(row[i])) {
                    return row[0];
                }
            }
        }
        return null;
    }
}
