package com.github.stormino.service.link;

/** Published when a provider becomes ready (e.g. after a warm-up), so waiting tasks can start. */
public record LinkProviderReadyEvent(String providerId) {
}
