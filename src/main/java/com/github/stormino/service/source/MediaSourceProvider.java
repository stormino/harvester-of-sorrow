package com.github.stormino.service.source;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ResolvedMedia;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strategy contract for a back-end content source (VixSrc, RaiPlay, ...).
 * One implementation per {@link MediaSource}. Resolved through
 * {@link MediaSourceRegistry} based on a task's source.
 */
public interface MediaSourceProvider {

    MediaSource source();

    /**
     * Free-text catalogue search. Returned {@link ContentMetadata} must have
     * its {@code source} and {@code sourceMetadata} populated so the caller
     * can route subsequent operations back to this provider.
     */
    List<ContentMetadata> search(String query, ContentTypeFilter filter);

    /**
     * Lightweight check used to filter search results. Implementations should
     * favour speed over accuracy here (HEAD requests, single-language probes).
     */
    AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages);

    /**
     * Resolve the playlist URL for a given task. The provider branches on
     * {@link DownloadTask#getContentType()} internally so callers don't have
     * to. Returned {@link PlaylistInfo} must include {@code referer}.
     */
    Optional<PlaylistInfo> getPlaylist(DownloadTask task, String language);

    /**
     * Fetch and parse the master HLS playlist for a task in one shot, so the
     * orchestrator can build sub-tasks from the actual renditions discovered in
     * the playlist rather than iterating over requested languages.
     *
     * <p>Providers that do not yet support this optimisation may return
     * {@link Optional#empty()} — the orchestrator will fall back to
     * language-driven initialisation in that case.
     */
    default Optional<ResolvedMedia> resolveMaster(DownloadTask task, String primaryLanguage) {
        return Optional.empty();
    }

    /**
     * Languages this source natively serves. The download dialog uses this
     * to constrain the language picker. An empty set means "no restriction".
     */
    Set<String> supportedLanguages();

    /**
     * Enumerate every episode of a TV show.  Used by the download queue when
     * the user requests "whole show" or "whole season" so per-episode tasks
     * can be created without the queue having to know how a given source
     * structures its catalogue (TMDB walks for VixSrc, program → contentset →
     * episodes.json traversal for RaiPlay, etc.).
     *
     * <p>Returns an empty list for movies or when the show can't be expanded
     * (e.g. metadata missing).  Default is empty so providers can opt in.
     */
    default List<EpisodeRef> listEpisodes(ContentMetadata show) {
        return List.of();
    }
}
