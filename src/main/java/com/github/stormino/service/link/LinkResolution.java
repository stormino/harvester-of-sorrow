package com.github.stormino.service.link;

import java.util.List;

/**
 * What a link points to.
 *
 * @param providerId provider that resolved it
 * @param url        the link
 * @param folder     whether it is a folder (several files)
 * @param name       file or folder name
 * @param files      downloadable files
 * @param truncated  files left out because the folder is too large
 */
public record LinkResolution(String providerId, String url, boolean folder, String name, List<LinkFile> files,
                             int truncated) {
    public LinkResolution {
        files = List.copyOf(files);
    }
}
