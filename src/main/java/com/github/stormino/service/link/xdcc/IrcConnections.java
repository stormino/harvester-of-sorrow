package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One IRC connection per server, opened on demand and closed after a while without transfers. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IrcConnections {

    private final AppProperties.Link.Xdcc config;
    private final List<IrcAccount> accounts;
    private final Map<String, IrcClient> clients = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inUse = new ConcurrentHashMap<>();

    public IrcConnections(AppProperties properties) {
        this.config = properties.getLink().getXdcc();
        this.accounts = IrcAccount.parse(config.getIrcAccounts(), config.getIrcRegisterEmail());
        if (!accounts.isEmpty()) {
            log.info("IRC: registered nicks configured for {}", accounts.stream().map(IrcAccount::server).toList());
        }
    }

    /** Connected client for {@code host:port}; marks it in use until {@link #release} is called. */
    public IrcClient acquire(String host, int port) throws IOException {
        String key = (host + ":" + port).toLowerCase();
        inUse.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        try {
            synchronized (this) {
                IrcClient c = clients.get(key);
                if (c == null || !c.isConnected()) {
                    c = new IrcClient(host, port, config.getNickPrefix(),
                            IrcAccount.forHost(accounts, host).orElse(null));
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
        AtomicInteger n = inUse.get(client.server().toLowerCase());
        if (n != null) {
            n.decrementAndGet();
        }
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
}
