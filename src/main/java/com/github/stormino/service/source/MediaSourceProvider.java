package com.github.stormino.service.source;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;

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
     * Languages this source natively serves. The download dialog uses this
     * to constrain the language picker. An empty set means "no restriction".
     */
    Set<String> supportedLanguages();
}
