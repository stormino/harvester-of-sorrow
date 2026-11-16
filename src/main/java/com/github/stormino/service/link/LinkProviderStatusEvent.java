package com.github.stormino.service.link;

/** Published (throttled) while a provider prepares, so waiting tasks can show its progress. */
public record LinkProviderStatusEvent(String providerId, ProviderStatus status) {
}
