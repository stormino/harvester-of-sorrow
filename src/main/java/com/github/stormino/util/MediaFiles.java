package com.github.stormino.util;

import java.util.Locale;
import java.util.Set;

/** File kinds by extension. */
public final class MediaFiles {

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv",
            ".ts", ".m2ts", ".mts", ".webm", ".mpg", ".mpeg", ".flv", ".ogv", ".3gp", ".vob", ".divx");
    public static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".ass", ".ssa", ".sub", ".vtt");

    private MediaFiles() {
    }

    public static boolean isVideo(String fileName) {
        return VIDEO_EXTENSIONS.contains(extension(fileName));
    }

    public static boolean isSubtitle(String fileName) {
        return SUBTITLE_EXTENSIONS.contains(extension(fileName));
    }

    /** Lower-case extension with the dot, or "" */
    public static String extension(String fileName) {
        return PathUtils.getExtension(fileName == null ? "" : fileName).toLowerCase(Locale.ROOT);
    }
}
