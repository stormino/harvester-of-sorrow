package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One IRC connection per server, opened on demand and closed after a while without transfers.
 * Remembers the networks whose channels needed the registered nick, to log in there while connecting.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IrcConnections {

    /** A network for the Settings screen: remembered or connected now. */
    public record NetworkStatus(String host, int port, boolean connected, String nick, IrcClient.AuthState state,
                                String message, boolean needsAccount) {
    }

    private final AppProperties.Link.Xdcc config;
    private final IrcIdentityService identity;
    private final Map<String, IrcClient> clients = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inUse = new ConcurrentHashMap<>();

    private final IrcClient.AuthListener authListener = new IrcClient.AuthListener() {
        @Override
        public void authState(IrcClient c, IrcClient.AuthState state, String message) {
            if (state != IrcClient.AuthState.ANONYMOUS) {
                identity.networkState(c.host(), c.port(), c.nick(), state, message);
            }
        }

        @Override
        public void needsAccount(IrcClient c) {
            if (!identity.needsAccount(c.host())) {
                log.info("IRC: {} has channels that need a registered nick; logging in there from now on", c.host());
                identity.networkState(c.host(), c.port(), c.nick(), c.authState(), null);
            }
        }
    };

    public IrcConnections(AppProperties properties, IrcIdentityService identity) {
        this.config = properties.getLink().getXdcc();
        this.identity = identity;
    }

    /** Connected client for {@code host:port}; marks it in use until {@link #release} is called. */
    public IrcClient acquire(String host, int port) throws IOException {
        String key = key(host, port);
        inUse.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        try {
            synchronized (this) {
                IrcClient c = clients.get(key);
                if (c == null || !c.isConnected()) {
                    boolean loginOnConnect = identity.needsAccount(host) && identity.account() != null;
                    c = new IrcClient(host, port, config.getNickPrefix(), identity::account, loginOnConnect, authListener);
                    c.connect(Duration.ofSeconds(45));
                    clients.put(key, c);
                }
                return c;
            }
        } catch (IOException | RuntimeException e) {
            inUse.get(key).decrementAndGet();
            throw e;
        }
    }

    public void release(IrcClient client) {
        AtomicInteger n = inUse.get(key(client.host(), client.port()));
        if (n != null) {
            n.decrementAndGet();
        }
    }

    /** Networks that needed the registered nick, plus the ones connected now. */
    public List<NetworkStatus> networks() {
        List<NetworkStatus> out = new ArrayList<>();
        List<IrcIdentityService.Network> remembered = identity.networks();
        for (IrcIdentityService.Network n : remembered) {
            Optional<IrcClient> live = live(n.host());
            out.add(live.map(c -> status(c, true))
                    .orElse(new NetworkStatus(n.host(), n.port(), false, n.nick(), n.state(), n.message(), true)));
        }
        clients.values().stream()
                .filter(IrcClient::isConnected)
                .filter(c -> remembered.stream().noneMatch(n -> n.host().equalsIgnoreCase(c.host())))
                .forEach(c -> out.add(status(c, false)));
        return out;
    }

    private static NetworkStatus status(IrcClient c, boolean needsAccount) {
        return new NetworkStatus(c.host().toLowerCase(Locale.ROOT), c.port(), true, c.nick(), c.authState(),
                c.authProblem(), needsAccount);
    }

    /**
     * Sends the e-mailed registration code to NickServ on {@code host} (connecting if needed).
     *
     * @throws IOException when the network is unknown or NickServ refuses the code
     */
    public NetworkStatus confirm(String host, String code) throws IOException {
        IrcIdentityService.Network n = identity.network(host)
                .orElseThrow(() -> new IOException("Unknown network " + host));
        IrcClient c = acquire(n.host(), n.port());
        try {
            c.confirm(code);
            return status(c, true);
        } finally {
            release(c);
        }
    }

    private Optional<IrcClient> live(String host) {
        return clients.values().stream()
                .filter(IrcClient::isConnected)
                .filter(c -> c.host().equalsIgnoreCase(host))
                .findFirst();
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void closeIdle() {
        long idle = Duration.ofMinutes(config.getIdleDisconnectMinutes()).toNanos();
        clients.forEach((key, c) -> {
            boolean unused = inUse.getOrDefault(key, new AtomicInteger()).get() <= 0;
            if (!c.isConnected() || unused && System.nanoTime() - c.lastActivity() > idle) {
                synchronized (this) {
                    if (clients.remove(key, c)) {
                        log.info("IRC: closing idle connection to {}", key);
                        c.close();
                    }
                }
            }
        });
    }

    @PreDestroy
    public void closeAll() {
        clients.values().forEach(IrcClient::close);
        clients.clear();
    }

    private static String key(String host, int port) {
        return (host + ":" + port).toLowerCase(Locale.ROOT);
    }
}
