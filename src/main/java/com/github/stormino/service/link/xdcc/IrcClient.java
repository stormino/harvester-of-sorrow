package com.github.stormino.service.link.xdcc;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal IRC client: registration (with a random nick, retried when taken), PING/PONG, joining
 * channels, tracking who is in them, and sending messages. Everything received is also handed to
 * the registered listeners (for XDCC: bot notices and CTCP DCC offers).
 */
@Slf4j
public final class IrcClient implements Closeable {

    /** Numerics that refuse a JOIN: no such channel, full, invite-only, banned, bad key, needs registration, ... */
    private static final Set<String> JOIN_ERRORS = Set.of("403", "405", "471", "473", "474", "475", "477", "489", "520");

    private final String host;
    private final int port;
    private final String nickPrefix;
    private final List<Consumer<IrcMessage>> listeners = new CopyOnWriteArrayList<>();
    /** channel (lower case) -> nicks (lower case) */
    private final Map<String, Set<String>> members = new ConcurrentHashMap<>();
    private final Map<String, JoinWait> joins = new ConcurrentHashMap<>();

    private Socket socket;
    private OutputStream out;
    private volatile String nick;
    private volatile boolean closed;
    private volatile long lastActivity = System.nanoTime();
    private final CountDownLatch registered = new CountDownLatch(1);

    private static final class JoinWait {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
    }

    public IrcClient(String host, int port, String nickPrefix) {
        this.host = host;
        this.port = port;
        this.nickPrefix = nickPrefix;
    }

    public String server() {
        return host + ":" + port;
    }

    public String nick() {
        return nick;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && !closed;
    }

    /** Nanotime of the last message sent or received. */
    public long lastActivity() {
        return lastActivity;
    }

    public void addListener(Consumer<IrcMessage> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<IrcMessage> l) {
        listeners.remove(l);
    }

    /** Connects and registers; returns once the server has welcomed us. */
    public void connect(Duration timeout) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) Math.min(timeout.toMillis(), 30_000));
        socket.setKeepAlive(true);
        out = socket.getOutputStream();
        nick = randomNick();
        Thread reader = new Thread(this::readLoop, "irc-" + host);
        reader.setDaemon(true);
        reader.start();
        send("NICK " + nick);
        send("USER " + nick + " 0 * :" + nick);
        try {
            if (!registered.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                close();
                throw new IOException("No welcome from " + server() + " within " + timeout.toSeconds() + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Interrupted while connecting to " + server());
        }
        if (closed) {
            throw new IOException("Disconnected from " + server() + " during registration");
        }
        log.info("IRC: connected to {} as {}", server(), nick);
    }

    /** Joins {@code channel} (no-op if already in it) and waits for the member list. */
    public void join(String channel, Duration timeout) throws IOException {
        String key = channel.toLowerCase(Locale.ROOT);
        if (members.containsKey(key)) {
            return;
        }
        JoinWait wait = joins.computeIfAbsent(key, k -> new JoinWait());
        send("JOIN " + channel);
        try {
            if (!wait.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Could not join " + channel + " (no answer)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while joining " + channel);
        } finally {
            joins.remove(key);
        }
        if (wait.error != null) {
            throw new IOException("Cannot join " + channel + ": " + wait.error);
        }
    }

    public boolean isIn(String channel) {
        return members.containsKey(channel.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code nick} is in {@code channel} (as far as we have seen). */
    public boolean isMember(String channel, String nick) {
        Set<String> m = members.get(channel.toLowerCase(Locale.ROOT));
        return m != null && m.contains(nick.toLowerCase(Locale.ROOT));
    }

    public void privmsg(String target, String text) throws IOException {
        send("PRIVMSG " + target + " :" + text);
    }

    public void ctcp(String target, String payload) throws IOException {
        privmsg(target, "\u0001" + payload + "\u0001");
    }

    public synchronized void send(String line) throws IOException {
        if (out == null || closed) {
            throw new IOException("Not connected to " + server());
        }
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        lastActivity = System.nanoTime();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            if (out != null) {
                send("QUIT :bye");
            }
        } catch (IOException ignored) {
            // closing anyway
        }
        closed = true;
        registered.countDown();
        joins.values().forEach(w -> {
            w.error = "disconnected";
            w.done.countDown();
        });
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private void readLoop() {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), decoder))) {
            String line;
            while ((line = in.readLine()) != null) {
                lastActivity = System.nanoTime();
                if (line.isEmpty()) {
                    continue;
                }
                IrcMessage m = IrcMessage.parse(line);
                try {
                    handle(m);
                } catch (Exception e) {
                    log.debug("IRC: error handling '{}': {}", line, e.getMessage());
                }
                for (Consumer<IrcMessage> l : listeners) {
                    try {
                        l.accept(m);
                    } catch (Exception e) {
                        log.debug("IRC listener failed: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.info("IRC: connection to {} lost: {}", server(), e.getMessage());
            }
        } finally {
            if (!closed) {
                log.info("IRC: disconnected from {}", server());
            }
            closed = true;
            registered.countDown();
            joins.values().forEach(w -> {
                w.error = "disconnected";
                w.done.countDown();
            });
        }
    }

    private void handle(IrcMessage m) throws IOException {
        switch (m.command()) {
            case "PING" -> send("PONG :" + (m.text() == null ? "" : m.text()));
            case "001" -> {
                if (m.param(0) != null) {
                    nick = m.param(0);
                }
                registered.countDown();
            }
            case "433", "432" -> { // nick in use / erroneous
                if (registered.getCount() > 0) {
                    nick = randomNick();
                    send("NICK " + nick);
                }
            }
            case "JOIN" -> {
                String ch = lower(m.param(0));
                if (ch == null) {
                    return;
                }
                if (nick.equalsIgnoreCase(m.nick())) {
                    members.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet());
                } else {
                    Set<String> s = members.get(ch);
                    if (s != null) {
                        s.add(lower(m.nick()));
                    }
                }
            }
            case "353" -> { // RPL_NAMREPLY: me = #chan :@op +voice nick
                String ch = lower(m.param(m.params().size() - 2));
                Set<String> s = members.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet());
                for (String n : m.text().trim().split(" +")) {
                    String clean = n.replaceFirst("^[~&@%+]+", "");
                    if (!clean.isEmpty()) {
                        s.add(lower(clean));
                    }
                }
            }
            case "366" -> { // end of NAMES
                JoinWait w = joins.get(lower(m.param(1)));
                if (w != null) {
                    w.done.countDown();
                }
            }
            case "PART" -> {
                String ch = lower(m.param(0));
                if (nick.equalsIgnoreCase(m.nick())) {
                    members.remove(ch);
                } else if (ch != null && members.containsKey(ch)) {
                    members.get(ch).remove(lower(m.nick()));
                }
            }
            case "KICK" -> {
                String ch = lower(m.param(0));
                if (nick.equalsIgnoreCase(m.param(1))) {
                    members.remove(ch);
                } else if (ch != null && members.containsKey(ch)) {
                    members.get(ch).remove(lower(m.param(1)));
                }
            }
            case "QUIT" -> members.values().forEach(s -> s.remove(lower(m.nick())));
            case "NICK" -> {
                String old = lower(m.nick());
                String now = lower(m.text());
                if (nick.equalsIgnoreCase(m.nick())) {
                    nick = m.text();
                }
                members.values().forEach(s -> {
                    if (s.remove(old)) {
                        s.add(now);
                    }
                });
            }
            default -> {
                if (JOIN_ERRORS.contains(m.command())) {
                    JoinWait w = joins.get(lower(m.param(1)));
                    if (w != null) {
                        w.error = IrcMessage.stripFormatting(m.text());
                        w.done.countDown();
                    }
                }
            }
        }
    }

    private String randomNick() {
        return nickPrefix + ThreadLocalRandom.current().nextInt(10_000, 99_999);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
