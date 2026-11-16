package com.github.stormino.util;

import java.nio.file.Paths;
import java.util.Locale;

/** Library layout for TV episodes: {@code <tv>/<Show.Year>/Season NN/<Show - SxxEyy - Name>.<ext>}. */
public final class LibraryPaths {

    private LibraryPaths() {
    }

    /** {@code <tvShowsPath>/<Show.Year>} */
    public static String tvShowDir(String tvShowsPath, String title, Integer year) {
        String showDir = PathUtils.sanitizeFilename(title);
        if (year != null) {
            showDir = showDir + "." + year;
        }
        return Paths.get(tvShowsPath, showDir).toString();
    }

    /**
     * Full path of an episode file. {@code extension} includes the dot (e.g. {@code .mkv}).
     * Folders are created only when the finished file is copied.
     */
    public static String tvEpisode(String tvShowsPath, String title, Integer year, int season, int episode,
                                   String episodeName, String extension) {
        String filename = episodeName != null
                ? String.format(DownloadConstants.TV_EPISODE_WITH_NAME_FORMAT, title, season, episode, episodeName)
                : String.format(DownloadConstants.TV_EPISODE_FORMAT, title, season, episode);
        filename = PathUtils.sanitizeFilename(filename) + extension;
        String seasonDir = String.format(DownloadConstants.SEASON_DIR_FORMAT, season);
        return Paths.get(tvShowDir(tvShowsPath, title, year), seasonDir, filename).toString();
    }

    /** Replaces the extension of {@code path} with the one of {@code fileName} (if it has a usable one). */
    public static String withExtension(String path, String fileName) {
        String ext = PathUtils.getExtension(fileName == null ? "" : fileName).toLowerCase(Locale.ROOT);
        if (ext.length() < 2 || ext.length() > 6 || !ext.substring(1).chars().allMatch(Character::isLetterOrDigit)) {
            return path;
        }
        String current = PathUtils.getExtension(path);
        return path.substring(0, path.length() - current.length()) + ext;
    }
}
