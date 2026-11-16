package com.github.stormino.service.link;

import java.nio.file.Path;

/**
 * A way of downloading files from a pasted link (Mega, a direct HTTP URL, ...).
 *
 * <p>Implementations are Spring beans collected by {@link LinkProviderRegistry}. The registry
 * picks, for a URL, the provider with the highest {@link #matchScore}; the user may also force one.
 */
public interface LinkProvider {

    /** Specific providers (one site) beat generic ones. */
    int SCORE_SPECIFIC = 100;
    int SCORE_GENERIC = 1;
    int SCORE_NONE = 0;

    /** Stable id, used in the API and stored with tasks ({@code mega}, {@code http}). */
    String id();

    String displayName();

    /** How well this provider handles {@code url}: {@link #SCORE_NONE} when it cannot. No network calls. */
    int matchScore(String url);

    /**
     * Describes what the link points to (one file, or the files of a folder) without downloading.
     *
     * @throws LinkException when the link is invalid, unreachable or not a file
     */
    LinkResolution resolve(String url);

    /**
     * Downloads one file into {@code workDir} as {@code targetName} and returns its path. Partial
     * data left in {@code workDir} by an earlier attempt is resumed where possible.
     *
     * @throws LinkCancelledException when {@code control} was cancelled
     * @throws LinkException          on any other failure
     */
    Path download(LinkFile file, Path workDir, String targetName, LinkProgress progress, LinkControl control);

    /** Whether downloads can start now. Resolving never waits for this. */
    default ProviderStatus status() {
        return ProviderStatus.ready(null);
    }

    /** Starts whatever {@link #status()} is waiting for (e.g. a proxy warm-up) in the background. */
    default void prepare() {
    }

    /** Files this provider downloads at the same time. */
    default int maxConcurrent() {
        return Integer.MAX_VALUE;
    }
}
