package com.github.stormino.model.source;

import com.github.stormino.model.MediaSource;

import java.util.List;

/**
 * Source metadata of a link download task: which provider fetches it and which file.
 *
 * @param providerId provider that resolved the link ({@code mega}, {@code http}, ...)
 * @param link       link as pasted by the user (file or folder link)
 * @param fileUrl    provider-specific URL of the one file this task downloads
 * @param fileName   file name reported by the provider
 * @param size       size in bytes, when known (all parts, for multi-part archives)
 * @param parts      every volume of a multi-part archive, first one included; {@code null} otherwise
 */
public record LinkMetadata(String providerId, String link, String fileUrl, String fileName, Long size,
                           List<Part> parts) implements SourceMetadata {

    /** One volume of a multi-part archive. */
    public record Part(String url, String fileName, Long size) {
    }

    @Override
    public MediaSource source() {
        return MediaSource.LINK;
    }
}
