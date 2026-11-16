package com.github.stormino.service.link;

/**
 * Readiness of a provider.
 *
 * @param state   see {@link State}
 * @param message human-readable detail (e.g. warm-up progress), may be {@code null}
 * @param done    progress of the current preparation step, when known
 * @param total   size of the current preparation step, when known
 */
public record ProviderStatus(State state, String message, Integer done, Integer total) {

    public enum State {
        /** Downloads start immediately. */
        READY,
        /** Not prepared yet; {@link LinkProvider#prepare()} starts it. */
        IDLE,
        /** Preparing (e.g. collecting proxies); downloads wait. */
        WARMING_UP,
        /** The last preparation failed; {@link LinkProvider#prepare()} retries it. */
        ERROR
    }

    public static ProviderStatus ready(String message) {
        return new ProviderStatus(State.READY, message, null, null);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
