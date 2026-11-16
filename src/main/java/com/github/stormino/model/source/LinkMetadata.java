package com.github.stormino.model.source;

import com.github.stormino.model.MediaSource;

/**
 * Source metadata of a link download task: which provider fetches it and which file.
 *
 * @param providerId provider that resolved the link ({@code mega}, {@code http}, ...)
 * @param link       link as pasted by the user (file or folder link)
 * @param fileUrl    provider-specific URL of the one file this task downloads
 * @param fileName   file name reported by the provider
 * @param size       size in bytes, when known
 */
public record LinkMetadata(String providerId, String link, String fileUrl, String fileName, Long size)
        implements SourceMetadata {

    @Override
    public MediaSource source() {
        return MediaSource.LINK;
    }
}
