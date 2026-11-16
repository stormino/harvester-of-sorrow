package com.github.stormino.service.link;

import com.github.stormino.model.DownloadTask;

/**
 * One file of a resolved link to queue, with the library identity the user confirmed.
 *
 * @param providerId  provider that resolved the link
 * @param link        link as pasted
 * @param file        the file to download
 * @param contentType movie or TV episode
 * @param tmdbId      confirmed TMDB match, or {@code null} for a manual title
 * @param title       movie or show title used for the library path
 * @param year        movie year / show first-air year
 * @param season      TV only
 * @param episode     TV only
 * @param episodeName TV only, optional
 */
public record LinkTaskSpec(String providerId, String link, LinkFile file, DownloadTask.ContentType contentType,
                           Integer tmdbId, String title, Integer year, Integer season, Integer episode,
                           String episodeName) {
}
