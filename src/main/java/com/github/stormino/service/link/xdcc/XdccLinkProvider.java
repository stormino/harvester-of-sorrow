package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
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
        progress.status("Connecting to " + (link.network() != null ? link.network() : link.server()));
        IrcClient irc;
        try {
            irc = connections.acquire(link.server(), link.port());
        } catch (IOException e) {
            throw new LinkException("Cannot connect to " + link.server() + ": " + e.getMessage(), e);
        }
        try {
            new XdccTransfer(irc, link, Duration.ofSeconds(config.getBotReplyTimeoutSeconds()),
                    Duration.ofMinutes(config.getQueueTimeoutMinutes())).run(part, progress, control);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new LinkException("Cannot finish " + targetName + ": " + e.getMessage(), e);
        } finally {
            connections.release(irc);
        }
    }
}
