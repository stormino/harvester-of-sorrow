package com.github.stormino.service.link;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All enabled {@link LinkProvider}s; picks one for a URL, or validates a user's choice. */
@Component
public class LinkProviderRegistry {

    private final Map<String, LinkProvider> providers = new LinkedHashMap<>();

    public LinkProviderRegistry(List<LinkProvider> providers) {
        providers.stream()
                .sorted(Comparator.comparing(LinkProvider::id))
                .forEach(p -> {
                    if (this.providers.put(p.id(), p) != null) {
                        throw new IllegalStateException("Duplicate link provider id: " + p.id());
                    }
                });
    }

    public List<LinkProvider> all() {
        return List.copyOf(providers.values());
    }

    public Optional<LinkProvider> find(String id) {
        return Optional.ofNullable(id == null ? null : providers.get(id));
    }

    /**
     * The provider for {@code url}: {@code forcedId} when given (it must accept the URL), otherwise
     * the one with the highest {@link LinkProvider#matchScore}.
     *
     * @throws LinkException when no provider (or not the forced one) handles the URL
     */
    public LinkProvider select(String url, String forcedId) {
        if (url == null || url.isBlank()) {
            throw new LinkException("The link is empty");
        }
        if (forcedId != null && !forcedId.isBlank()) {
            LinkProvider p = find(forcedId)
                    .orElseThrow(() -> new LinkException("Unknown or disabled provider: " + forcedId));
            if (p.matchScore(url) <= LinkProvider.SCORE_NONE) {
                throw new LinkException(p.displayName() + " cannot handle this link");
            }
            return p;
        }
        return providers.values().stream()
                .filter(p -> p.matchScore(url) > LinkProvider.SCORE_NONE)
                .max(Comparator.comparingInt(p -> p.matchScore(url)))
                .orElseThrow(() -> new LinkException("No provider can handle this link"));
    }
}
