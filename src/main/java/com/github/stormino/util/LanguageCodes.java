package com.github.stormino.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;

/**
 * Converts between the language codes HoS meets: two-letter ISO 639-1 ("it") used by
 * the API and the UI, three-letter ISO 639-2 ("ita") used by HLS playlists and by
 * container metadata, which Matroska and Jellyfin expect.
 */
public final class LanguageCodes {

    /** Withdrawn ISO 639-1 codes Java still returns → current ones. */
    private static final Map<String, String> MODERN = Map.of("iw", "he", "in", "id", "ji", "yi");

    /** ISO 639-2 code → ISO 639-1 code, for every language Java knows. */
    private static final Map<String, String> ISO3_TO_ISO1 = new HashMap<>();

    static {
        for (String iso1 : Locale.getISOLanguages()) {
            try {
                ISO3_TO_ISO1.put(Locale.of(iso1).getISO3Language(), iso1);
            } catch (MissingResourceException ignored) {
                // no three-letter code for this language
            }
        }
        // ISO 639-2/B ("bibliographic") variants that differ from the /T codes Java returns
        Map.ofEntries(Map.entry("ger", "de"), Map.entry("fre", "fr"), Map.entry("chi", "zh"),
                Map.entry("dut", "nl"), Map.entry("gre", "el"), Map.entry("cze", "cs"), Map.entry("rum", "ro"),
                Map.entry("slo", "sk"), Map.entry("per", "fa"), Map.entry("alb", "sq"), Map.entry("may", "ms"),
                Map.entry("arm", "hy"), Map.entry("baq", "eu"), Map.entry("bur", "my"), Map.entry("geo", "ka"),
                Map.entry("ice", "is"), Map.entry("mac", "mk"), Map.entry("mao", "mi"), Map.entry("tib", "bo"),
                Map.entry("wel", "cy")).forEach(ISO3_TO_ISO1::put);
    }

    private LanguageCodes() {}

    /** Markers VixSrc puts around the language code ("ita-forced", "forced-ita"). */
    private static final java.util.Set<String> MARKERS = java.util.Set.of("forced", "sdh", "cc", "hi");

    /**
     * "ita", "it", "it-IT", "ita-forced" / "forced-ita" (VixSrc), "IT" → "it".
     * Empty for null, blank, "und" or unknown codes.
     */
    public static Optional<String> toIso1(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = java.util.Arrays.stream(code.trim().toLowerCase(Locale.ROOT).split("[-_]"))
                .filter(t -> !t.isEmpty() && !MARKERS.contains(t))
                .findFirst().orElse("");
        String iso1 = c.length() == 2 ? c : ISO3_TO_ISO1.get(c);
        return Optional.ofNullable(iso1).map(l -> MODERN.getOrDefault(l, l));
    }

    /** "it", "ita", "it-IT" → "ita" (what MKV/MP4 language tags expect). "und" when unknown. */
    public static String toIso3(String code) {
        return toIso1(code).map(c -> {
            try {
                return Locale.of(c).getISO3Language();
            } catch (MissingResourceException e) {
                return "und";
            }
        }).filter(c -> !c.isEmpty()).orElse("und");
    }

    /** "it" / "ita" → "Italian". Falls back to the code itself. */
    public static String displayName(String code) {
        return toIso1(code)
                .map(c -> Locale.of(c).getDisplayLanguage(Locale.ENGLISH))
                .filter(n -> !n.isBlank())
                .orElse(code == null ? "Unknown" : code);
    }

    /** True when the code is one HoS can name ("it", "ita", "ita-forced"; not "und" or "xyz"). */
    public static boolean isKnown(String code) {
        return toIso1(code).map(c -> !Locale.of(c).getDisplayLanguage(Locale.ENGLISH).equals(c)).orElse(false);
    }

    /** True when both codes name the same language, whatever form they are in. */
    public static boolean same(String a, String b) {
        Optional<String> x = toIso1(a);
        return x.isPresent() && x.equals(toIso1(b));
    }
}
