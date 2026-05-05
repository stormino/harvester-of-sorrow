package com.github.stormino.service.source;

import com.github.stormino.model.MediaSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves {@link MediaSourceProvider} implementations by {@link MediaSource}.
 * Spring discovers all {@link MediaSourceProvider} beans on the classpath and
 * injects them here.
 */
@Slf4j
@Component
public class MediaSourceRegistry {

    private final Map<MediaSource, MediaSourceProvider> providers;

    public MediaSourceRegistry(List<MediaSourceProvider> providers) {
        Map<MediaSource, MediaSourceProvider> map = new EnumMap<>(MediaSource.class);
        for (MediaSourceProvider provider : providers) {
            MediaSourceProvider existing = map.put(provider.source(), provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "Multiple providers registered for source " + provider.source()
                                + ": " + existing.getClass().getName()
                                + " and " + provider.getClass().getName());
            }
        }
        this.providers = Map.copyOf(map);
        log.info("Registered {} media source provider(s): {}",
                this.providers.size(), this.providers.keySet());
    }

    public MediaSourceProvider get(MediaSource source) {
        MediaSourceProvider provider = providers.get(source);
        if (provider == null) {
            throw new IllegalStateException("No provider registered for source " + source);
        }
        return provider;
    }

    public List<MediaSourceProvider> all() {
        return List.copyOf(providers.values());
    }
}
