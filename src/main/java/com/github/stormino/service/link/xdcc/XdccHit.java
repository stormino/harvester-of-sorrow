package com.github.stormino.service.link.xdcc;

/**
 * One pack found by a search source.
 *
 * @param source      where it was found ("PW Search Engine", "xdcc.eu")
 * @param size        size as listed (e.g. "1.4G")
 * @param approxBytes {@code size} in bytes, approximate
 * @param link        everything needed to request it
 */
public record XdccHit(String source, String network, String channel, String bot, int pack, String size,
                      Long approxBytes, String fileName, XdccLink link) {

    /** Same pack (server, channel, bot, number), whichever source listed it. */
    public String key() {
        return (link.server() + "|" + link.channel() + "|" + bot + "|" + pack).toLowerCase(java.util.Locale.ROOT);
    }
}
