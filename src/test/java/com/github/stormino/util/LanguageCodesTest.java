package com.github.stormino.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageCodesTest {

    @Test
    void convertsBetweenTwoAndThreeLetterCodes() {
        assertEquals(Optional.of("it"), LanguageCodes.toIso1("ita"));
        assertEquals(Optional.of("en"), LanguageCodes.toIso1("eng"));
        assertEquals(Optional.of("de"), LanguageCodes.toIso1("ger"));  // ISO 639-2/B
        assertEquals(Optional.of("pt"), LanguageCodes.toIso1("pt-BR"));
        assertEquals(Optional.of("it"), LanguageCodes.toIso1("ita-forced"));  // VixSrc's forced tracks,
        assertEquals(Optional.of("it"), LanguageCodes.toIso1("forced-ita"));  // both ways round
        assertEquals(Optional.of("ms"), LanguageCodes.toIso1("may"));
        assertEquals(Optional.of("he"), LanguageCodes.toIso1("heb"));
        assertEquals(Optional.of("id"), LanguageCodes.toIso1("ind"));
        assertEquals("heb", LanguageCodes.toIso3("he"));
        assertEquals(Optional.empty(), LanguageCodes.toIso1("und"));
        assertEquals("ita", LanguageCodes.toIso3("it"));
        assertEquals("eng", LanguageCodes.toIso3("eng"));
        assertEquals("ita", LanguageCodes.toIso3("ita-forced"));
        assertEquals("und", LanguageCodes.toIso3(null));
    }

    @Test
    void knowsWhichCodesItCanName() {
        assertTrue(LanguageCodes.isKnown("est"));
        assertTrue(LanguageCodes.isKnown("forced-ita"));
        assertFalse(LanguageCodes.isKnown("und"));
        assertFalse(LanguageCodes.isKnown("xx"));
    }

    @Test
    void namesLanguagesInEnglish() {
        assertEquals("Italian", LanguageCodes.displayName("ita"));
        assertEquals("English", LanguageCodes.displayName("en"));
    }

    @Test
    void comparesAcrossForms() {
        assertTrue(LanguageCodes.same("it", "ita"));
        assertFalse(LanguageCodes.same("it", "eng"));
        assertFalse(LanguageCodes.same(null, null));
    }
}
