package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import com.github.stormino.service.link.LinkCancelledException;
import com.github.stormino.service.link.LinkControl;
import com.github.stormino.service.link.LinkException;
import com.github.stormino.service.link.LinkFile;
import com.github.stormino.service.link.LinkProgress;
import com.github.stormino.service.link.LinkProvider;
import com.github.stormino.service.link.LinkResolution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * XDCC packs on IRC, as {@code xdcc://} links (see {@link XdccLink}): connects to the network,
 * joins the bot's channel, requests the pack and receives it over DCC, resuming partial files.
 */
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XdccLinkProvider implements LinkProvider {

    public static final String ID = "xdcc";
    /** With other bots offering the same file, wait at most this long in one bot's queue. */
    static final Duration MIRROR_QUEUE_PATIENCE = Duration.ofMinutes(15);

    private final IrcConnections connections;
    private final AppProperties.Link.Xdcc config;

    public XdccLinkProvider(IrcConnections connections, AppProperties properties) {
        this.connections = connections;
        this.config = properties.getLink().getXdcc();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "XDCC";
    }

    @Override
    public int matchScore(String url) {
        return XdccLink.parse(url).isPresent() ? SCORE_SPECIFIC : SCORE_NONE;
    }

    @Override
    public int maxConcurrent() {
        return config.getMaxConcurrent();
    }

    /** No network call: everything is in the link (it comes from the pack list). */
    @Override
    public LinkResolution resolve(String url) {
        XdccLink link = XdccLink.parse(url).orElseThrow(() -> new LinkException("Not an XDCC link"));
        String name = link.fileName() != null ? link.fileName() : link.bot() + "-pack" + link.pack();
        return new LinkResolution(ID, url, false, name, List.of(new LinkFile(url, name, link.size(), List.of())), 0);
    }

    @Override
    public Path download(LinkFile file, Path workDir, String targetName, LinkProgress progress, LinkControl control) {
        XdccLink link = XdccLink.parse(file.url()).orElseThrow(() -> new LinkException("Not an XDCC link"));
        Path target = workDir.resolve(targetName).normalize();
        if (!workDir.toAbsolutePath().normalize().equals(target.toAbsolutePath().getParent())) {
            throw new LinkException("Unsafe file name: " + targetName);
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(workDir);
            if (Files.isRegularFile(target) && !Files.exists(part)) {
                return target; // finished earlier (the .part is renamed only when complete)
            }
        } catch (IOException e) {
            throw new LinkException("Cannot prepare " + workDir + ": " + e.getMessage(), e);
        }
        List<XdccLink> candidates = link.candidates();
        LinkException last = null;
        for (int i = 0; i < candidates.size(); i++) {
            XdccLink bot = candidates.get(i);
            boolean more = i < candidates.size() - 1;
            if (i > 0) {
                progress.status("Trying another bot (" + (i + 1) + " of " + candidates.size() + "): " + bot.bot());
            }
            try {
                transfer(bot, part, more, progress, control);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (LinkCancelledException e) {
                throw e;
            } catch (LinkException e) {
                last = e;
                if (!more) {
                    break;
                }
                org.slf4j.LoggerFactory.getLogger(XdccLinkProvider.class)
                        .info("XDCC {} failed ({}), trying the next bot", bot.bot(), e.getMessage());
            } catch (IOException e) {
                throw new LinkException("Cannot finish " + targetName + ": " + e.getMessage(), e);
            }
        }
        throw candidates.size() > 1 && last != null
                ? new LinkException(last.getMessage() + " (all " + candidates.size() + " bots tried)", last)
                : last;
    }

    /**
     * One bot. With other bots still to try, a long queue is not worth waiting for: give up after
     * {@link #MIRROR_QUEUE_PATIENCE} and move on.
     */
    private void transfer(XdccLink bot, Path part, boolean moreBots, LinkProgress progress, LinkControl control) {
        progress.status("Connecting to " + (bot.network() != null ? bot.network() : bot.server()));
        IrcClient irc;
        try {
            irc = connections.acquire(bot.server(), bot.port());
        } catch (IOException e) {
            throw new LinkException("Cannot connect to " + bot.server() + ": " + e.getMessage(), e);
        }
        try {
            Duration queue = Duration.ofMinutes(config.getQueueTimeoutMinutes());
            if (moreBots && queue.compareTo(MIRROR_QUEUE_PATIENCE) > 0) {
                queue = MIRROR_QUEUE_PATIENCE;
            }
            new XdccTransfer(irc, bot, Duration.ofSeconds(config.getBotReplyTimeoutSeconds()), queue)
                    .run(part, progress, control);
        } finally {
            connections.release(irc);
        }
    }
}
