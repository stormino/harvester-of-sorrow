package com.github.stormino.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Comparing titles the way people type them: no accents, case or punctuation. */
public final class TitleMatch {

    private TitleMatch() {}

    /** "L'Été dernier!" → "l ete dernier". */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    /**
     * Words to search XDCC file names by: no punctuation or accents. xdcc.eu finds nothing for
     * "Dune - Parte due" or "L'ultimo dei Mohicani", and finds "Dune Parte due" and "L ultimo dei Mohicani".
     */
    public static String searchWords(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    /**
     * Whether the title has every word of the query. The last word may be cut short (still typing)
     * and a year in the query is ignored, since titles rarely contain it: "Se domani non torno" keeps
     * "Se domani non torno" and drops "A domani"; "montalbano" keeps "Il commissario Montalbano".
     */
    public static boolean hasAllWords(String title, String query) {
        List<String> words = Arrays.stream(normalize(query).split(" "))
                .filter(w -> !w.isEmpty() && !w.matches("(19|20)\\d{2}"))
                .toList();
        if (words.isEmpty()) {
            return true;
        }
        String n = normalize(title);
        Set<String> titleWords = Arrays.stream(n.split(" ")).collect(Collectors.toSet());
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            boolean last = i == words.size() - 1;
            boolean found = titleWords.contains(w) || (last && titleWords.stream().anyMatch(t -> t.startsWith(w)));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Same title once normalised. */
    public static boolean same(String a, String b) {
        String na = normalize(a);
        return !na.isEmpty() && na.equals(normalize(b));
    }

    /** Years one apart still match (festival vs. cinema release); a missing year matches anything. */
    public static boolean yearsMatch(Integer a, Integer b) {
        return a == null || b == null || Math.abs(a - b) <= 1;
    }
}
