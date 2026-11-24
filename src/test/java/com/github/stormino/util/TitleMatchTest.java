package com.github.stormino.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleMatchTest {

    @Test
    void normalizesAccentsCaseAndPunctuation() {
        assertThat(TitleMatch.normalize("L'Été dernier!")).isEqualTo("l ete dernier");
        assertThat(TitleMatch.normalize("Fast & Furious")).isEqualTo("fast and furious");
        assertThat(TitleMatch.normalize(null)).isEmpty();
    }

    @Test
    void keepsTitlesWithEveryWordOfTheQuery() {
        String q = "Se domani non torno";
        assertThat(TitleMatch.hasAllWords("Se domani non torno", q)).isTrue();
        // What RaiPlay returned for this query
        assertThat(TitleMatch.hasAllWords("A domani", q)).isFalse();
        assertThat(TitleMatch.hasAllWords("Sistemo l'America e torno", q)).isFalse();
        assertThat(TitleMatch.hasAllWords("Il Grande Torino", q)).isFalse();
        assertThat(TitleMatch.hasAllWords("Da domani mi alzo tardi", q)).isFalse();
    }

    @Test
    void wordsAreWholeExceptTheLastOne() {
        assertThat(TitleMatch.hasAllWords("Il commissario Montalbano", "montalbano")).isTrue();
        assertThat(TitleMatch.hasAllWords("Il commissario Montalbano", "commissario montal")).isTrue();
        // "torno" is not "torino", and a word in the middle must be whole
        assertThat(TitleMatch.hasAllWords("Il Grande Torino", "torno")).isFalse();
        assertThat(TitleMatch.hasAllWords("Il commissario Montalbano", "commiss montalbano")).isFalse();
    }

    @Test
    void ignoresAYearInTheQuery() {
        assertThat(TitleMatch.hasAllWords("Dune", "dune 2021")).isTrue();
        assertThat(TitleMatch.hasAllWords("anything", "  ")).isTrue();
    }

    @Test
    void sameTitleAndCloseYears() {
        assertThat(TitleMatch.same("Se domani non torno", "SE DOMANI, NON TORNO")).isTrue();
        assertThat(TitleMatch.same("", "")).isFalse();
        assertThat(TitleMatch.yearsMatch(2025, 2026)).isTrue();
        assertThat(TitleMatch.yearsMatch(2024, 2026)).isFalse();
        assertThat(TitleMatch.yearsMatch(null, 2026)).isTrue();
    }

    @Test
    void searchWordsDropPunctuationAndAccents() {
        assertThat(TitleMatch.searchWords("Dune - Parte due")).isEqualTo("Dune Parte due");
        assertThat(TitleMatch.searchWords("Dune: Part Two")).isEqualTo("Dune Part Two");
        assertThat(TitleMatch.searchWords("L'ultimo dei Mohicani")).isEqualTo("L ultimo dei Mohicani");
        assertThat(TitleMatch.searchWords("Amélie")).isEqualTo("Amelie");
    }
}
