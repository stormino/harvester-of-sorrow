package com.github.stormino.service.link;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkProviderRegistryTest {

    record Fake(String id, int score) implements LinkProvider {
        @Override public String displayName() { return id; }
        @Override public int matchScore(String url) { return url.contains(id) || id.equals("http") ? score : 0; }
        @Override public LinkResolution resolve(String url) { throw new UnsupportedOperationException(); }
        @Override public Path download(LinkFile f, Path d, String n, LinkProgress p, LinkControl c) {
            throw new UnsupportedOperationException();
        }
    }

    private final LinkProviderRegistry registry = new LinkProviderRegistry(List.of(
            new Fake("http", LinkProvider.SCORE_GENERIC), new Fake("mega", LinkProvider.SCORE_SPECIFIC)));

    @Test
    void specificProviderWinsOverGeneric() {
        assertEquals("mega", registry.select("https://mega.nz/file/x#y", null).id());
        assertEquals("http", registry.select("https://example.com/a.mkv", null).id());
    }

    @Test
    void forcedProviderIsUsedWhenItAcceptsTheLink() {
        assertEquals("http", registry.select("https://mega.nz/file/x#y", "http").id());
        LinkException e = assertThrows(LinkException.class, () -> registry.select("https://example.com/a", "mega"));
        assertEquals("mega cannot handle this link", e.getMessage());
        assertThrows(LinkException.class, () -> registry.select("https://example.com/a", "nope"));
        assertThrows(LinkException.class, () -> registry.select(" ", null));
    }
}
