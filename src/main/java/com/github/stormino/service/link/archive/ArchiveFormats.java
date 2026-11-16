package com.github.stormino.service.link.archive;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises archive files and the volumes of multi-part archives by name:
 * <ul>
 *   <li>RAR: {@code x.rar}, {@code x.part1.rar} .. {@code x.partN.rar}, old style {@code x.rar} + {@code x.r00} ..</li>
 *   <li>7z / zip split: {@code x.7z.001}, {@code x.zip.001} ..; spanned zip {@code x.z01} .. + {@code x.zip}</li>
 *   <li>single {@code x.7z}, {@code x.zip}</li>
 * </ul>
 */
public final class ArchiveFormats {

    /** Which tool opens it. */
    public enum Kind { RAR, SEVEN_ZIP }

    /**
     * One file of an archive.
     *
     * @param set   name shared by all volumes of the archive (without volume suffixes)
     * @param kind  tool family
     * @param order position in the set; the volume to open is the one with the lowest order
     */
    public record Volume(String set, Kind kind, int order) {
    }

    private static final Pattern RAR_PART = Pattern.compile("(?i)^(.+)\\.part(\\d{1,4})\\.rar$");
    private static final Pattern RAR = Pattern.compile("(?i)^(.+)\\.rar$");
    private static final Pattern RAR_OLD = Pattern.compile("(?i)^(.+)\\.r(\\d{2,3})$");
    private static final Pattern SPLIT = Pattern.compile("(?i)^(.+\\.(?:7z|zip))\\.(\\d{3})$");
    private static final Pattern SEVEN_Z = Pattern.compile("(?i)^(.+)\\.7z$");
    private static final Pattern ZIP = Pattern.compile("(?i)^(.+)\\.zip$");
    private static final Pattern ZIP_SPAN = Pattern.compile("(?i)^(.+)\\.z(\\d{2})$");

    private ArchiveFormats() {
    }

    public static boolean isArchive(String fileName) {
        return volume(fileName).isPresent();
    }

    public static Optional<Volume> volume(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String n = fileName.strip();
        Matcher m;
        if ((m = RAR_PART.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1), Kind.RAR, Integer.parseInt(m.group(2))));
        }
        if ((m = RAR.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1), Kind.RAR, 0));
        }
        if ((m = RAR_OLD.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1), Kind.RAR, Integer.parseInt(m.group(2)) + 1));
        }
        if ((m = SPLIT.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1), Kind.SEVEN_ZIP, Integer.parseInt(m.group(2))));
        }
        if ((m = SEVEN_Z.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1) + ".7z", Kind.SEVEN_ZIP, 0));
        }
        if ((m = ZIP.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1) + ".zip", Kind.SEVEN_ZIP, 0));
        }
        if ((m = ZIP_SPAN.matcher(n)).matches()) {
            return Optional.of(new Volume(m.group(1) + ".zip", Kind.SEVEN_ZIP, Integer.parseInt(m.group(2))));
        }
        return Optional.empty();
    }

    /** Archive name without archive/volume suffixes ("Show.S01.part1.rar" -> "Show.S01"). */
    public static String baseName(String fileName) {
        return volume(fileName)
                .map(v -> v.set().replaceAll("(?i)\\.(7z|zip)$", ""))
                .orElse(fileName);
    }
}
