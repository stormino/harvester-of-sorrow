package com.github.stormino.api.v1;

import com.github.stormino.service.link.LinkFile;
import com.github.stormino.service.link.LinkResolution;
import com.github.stormino.service.link.ReleaseNameParser;

import java.util.List;

/** Test access to LinksController's package-private helpers. */
public final class LinksControllerAccess {
    private LinksControllerAccess() {
    }

    /** "firstName:parts:size" per entry */
    public static List<String> group(List<LinkFile> files) {
        return LinksController.groupArchiveVolumes(files).stream()
                .map(g -> g.first().fileName() + ":" + g.parts().size() + ":" + g.size()).toList();
    }

    public static ReleaseNameParser.Guess guess(LinkFile f) {
        return LinksController.guess(f);
    }

    public static ReleaseNameParser.Guess overall(LinkResolution r) {
        return LinksController.overallGuess(r);
    }
}
