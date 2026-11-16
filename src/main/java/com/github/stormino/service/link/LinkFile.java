package com.github.stormino.service.link;

import java.util.List;

/**
 * One downloadable file behind a link.
 *
 * @param url      provider-specific URL of this file (for folders, not the folder link)
 * @param fileName file name reported by the provider
 * @param size     size in bytes, or {@code null} when unknown
 * @param folders  folders between the shared root and the file (empty for single files)
 */
public record LinkFile(String url, String fileName, Long size, List<String> folders) {
    public LinkFile {
        folders = folders == null ? List.of() : List.copyOf(folders);
    }
}
