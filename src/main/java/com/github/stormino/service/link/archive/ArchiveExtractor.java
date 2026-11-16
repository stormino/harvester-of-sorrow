package com.github.stormino.service.link.archive;

import com.github.stormino.service.link.LinkCancelledException;
import com.github.stormino.service.link.LinkControl;
import com.github.stormino.service.link.LinkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unpacks archives with the system tools: {@code unrar} for RAR (RAR5 and multi-part sets),
 * 7-Zip ({@code 7zz} or {@code 7z}) for zip and 7z, split ({@code .001}) or not. Both are installed in the
 * Docker image. Password-protected archives are refused, never prompted for.
 */
@Slf4j
@Component
public class ArchiveExtractor {

    static final String UNRAR = "unrar";
    /** 7-Zip's binary is "7zz" in Ubuntu 22.04's 7zip package and "7z" in 24.04's. */
    private static final List<String> SEVEN_ZIP_NAMES = List.of("7zz", "7z");
    private static volatile String sevenZip;
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})%");
    /** Passed as password so encrypted archives fail instead of waiting for input; ignored otherwise. */
    private static final String NO_PASSWORD = "hos-no-password";

    /**
     * Extracts {@code archive} (the first volume of a set; other volumes must sit next to it) into
     * {@code target}, which is emptied first.
     *
     * @param progress receives 0-100 while extracting
     * @throws LinkCancelledException when cancelled
     * @throws LinkException          with a user-facing message on any failure
     */
    public void extract(Path archive, ArchiveFormats.Kind kind, Path target, IntConsumer progress, LinkControl control) {
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new LinkException("Cannot prepare " + target + ": " + e.getMessage(), e);
        }
        List<String> command = kind == ArchiveFormats.Kind.RAR
                ? List.of(UNRAR, "x", "-o+", "-y", "-idc", "-p" + NO_PASSWORD, archive.toString(), target + File.separator)
                : List.of(sevenZipBinary(), "x", "-y", "-bso1", "-bse1", "-bsp1", "-p" + NO_PASSWORD,
                        "-o" + target, archive.toString());
        log.info("Unpacking {} with {}", archive.getFileName(), command.get(0));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .start();
        } catch (IOException e) {
            throw new LinkException("Cannot unpack: '" + command.get(0) + "' is not installed", e);
        }
        control.onCancel(process::destroyForcibly);
        String output = readProgress(process.getInputStream(), progress);
        int exit;
        try {
            exit = process.waitFor(); // output already read to the end: the tool has finished
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LinkCancelledException("Interrupted");
        }
        if (control.isCancelled()) {
            throw new LinkCancelledException("Cancelled");
        }
        if (exit != 0) {
            log.warn("{} exited with {}: {}", command.get(0), exit, tail(output));
            throw new LinkException(explain(output, exit));
        }
        progress.accept(100);
    }

    /** The installed 7-Zip binary ("7zz" when none is found, so errors name the usual one). */
    public static String sevenZipBinary() {
        String found = sevenZip;
        if (found != null) {
            return found;
        }
        for (String name : SEVEN_ZIP_NAMES) {
            try {
                Process p = new ProcessBuilder(name).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                p.waitFor();
                sevenZip = name;
                return name;
            } catch (IOException e) {
                // not installed under this name
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return SEVEN_ZIP_NAMES.get(0);
    }

    /** Streams the tool output, reporting the last percentage seen; returns the full text. */
    private static String readProgress(InputStream in, IntConsumer progress) {
        StringBuilder all = new StringBuilder();
        byte[] buf = new byte[4096];
        int last = -1;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                all.append(chunk);
                Matcher m = PERCENT.matcher(chunk);
                int pct = -1;
                while (m.find()) {
                    pct = Integer.parseInt(m.group(1));
                }
                if (pct >= 0 && pct <= 100 && pct != last) {
                    last = pct;
                    progress.accept(pct);
                }
                if (all.length() > 200_000) {
                    all.delete(0, all.length() - 100_000);
                }
            }
        } catch (IOException e) {
            // process killed (cancel): handled by the caller
        }
        return all.toString();
    }

    /** Turns tool output into a message for the user. */
    static String explain(String output, int exit) {
        String o = output.toLowerCase(Locale.ROOT);
        if (o.contains("wrong password") || o.contains("incorrect password") || o.contains("encrypted")
                || o.contains("password is incorrect") || o.contains("enter password")) {
            return "The archive is password-protected";
        }
        if (o.contains("cannot find volume") || o.contains("missing volume") || o.contains("unexpected end of archive")
                || o.contains("need to start extraction from a previous volume") || o.contains("there are some data after the end")
                || o.contains("unexpected end of data")) {
            return "The archive is incomplete (missing or truncated parts)";
        }
        if (o.contains("checksum error") || o.contains("crc failed") || o.contains("data error")
                || o.contains("is not rar archive") || o.contains("cannot open the file as archive")
                || o.contains("is not archive") || o.contains("headers error")) {
            return "The archive is corrupt or not a supported archive";
        }
        if (o.contains("no space left")) {
            return "Not enough disk space to unpack the archive";
        }
        String last = tail(output);
        return "Unpacking failed (exit code " + exit + ")" + (last.isBlank() ? "" : ": " + last);
    }

    private static String tail(String output) {
        String[] lines = output.replace('\b', ' ').strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (!l.isEmpty() && !PERCENT.matcher(l).matches()) {
                return l.length() > 200 ? l.substring(0, 200) : l;
            }
        }
        return "";
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
