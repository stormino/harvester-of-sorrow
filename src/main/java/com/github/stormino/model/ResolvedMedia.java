package com.github.stormino.model;

import com.github.stormino.service.HlsParserService;

/**
 * Result of resolving a master playlist for a download task.
 * Carries the already-fetched {@link PlaylistInfo} (master URL + referer) together
 * with the parsed {@link HlsParserService.HlsPlaylist} so that
 * {@link com.github.stormino.service.TrackDownloadOrchestrator} can build sub-tasks
 * directly from the discovered audio/subtitle renditions without re-fetching the master
 * for every track.
 */
public record ResolvedMedia(PlaylistInfo playlist, HlsParserService.HlsPlaylist parsed) {}
