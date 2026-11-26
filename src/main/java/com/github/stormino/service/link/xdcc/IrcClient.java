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
import java.util.Base64;
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
 * Minimal IRC client: registration (with a random nick, retried when taken, or the nick of a
 * registered {@link IrcAccount}, logged in via SASL PLAIN or NickServ IDENTIFY and optionally
 * registered with NickServ first), PING/PONG, joining channels, tracking who is in them, and
 * sending messages. Everything received is also handed to
 * the registered listeners (for XDCC: bot notices and CTCP DCC offers).
 */
@Slf4j
public final class IrcClient implements Closeable {

    /** Numerics that refuse a JOIN: no such channel, full, invite-only, banned, bad key, needs registration, ... */
    private static final Set<String> JOIN_ERRORS = Set.of("403", "405", "471", "473", "474", "475", "477", "489", "520");

    private final String host;
    private final int port;
    private final String nickPrefix;
    private final IrcAccount account;
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
    /** Counted down once logged in to the services account, or once that has clearly failed. */
    private final CountDownLatch identified = new CountDownLatch(1);
    private volatile boolean loggedIn;
    private volatile boolean capEnded;
    private volatile boolean registerSent;
    private volatile String authProblem;

    private static final class JoinWait {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
        volatile String code;
    }

    public IrcClient(String host, int port, String nickPrefix) {
        this(host, port, nickPrefix, null);
    }

    /** {@code account}: registered nick to log in with, or null for a random unregistered nick. */
    public IrcClient(String host, int port, String nickPrefix, IrcAccount account) {
        this.host = host;
        this.port = port;
        this.nickPrefix = nickPrefix;
        this.account = account;
    }

    /** Whether we are logged in to a services account (registered nick). */
    public boolean isLoggedIn() {
        return loggedIn;
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
        nick = account != null ? account.nick() : randomNick();
        Thread reader = new Thread(this::readLoop, "irc-" + host);
        reader.setDaemon(true);
        reader.start();
        if (account != null) {
            send("CAP REQ :sasl"); // holds registration until CAP END
        }
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
        if (account != null) {
            identify(Duration.ofSeconds(30));
        }
    }

    /**
     * Logs in to the account with NickServ unless SASL already did; failures are only logged, as
     * most channels do not need it (the JOIN error then says why).
     */
    private void identify(Duration timeout) throws IOException {
        if (!loggedIn) {
            send("PRIVMSG NickServ :IDENTIFY " + account.nick() + " " + account.password());
        }
        try {
            if (!identified.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && authProblem == null) {
                authProblem = "no answer from NickServ";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while logging in to " + server());
        }
        if (loggedIn) {
            log.info("IRC: logged in to {} as {}", server(), account.nick());
        } else {
            log.warn("IRC: could not log in to {} as {}: {}", server(), account.nick(), authProblem);
        }
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
            String hint = "";
            if ("477".equals(wait.code) && !loggedIn) {
                hint = account == null
                        ? " (add a registered nick for " + host + " to XDCC_IRC_ACCOUNTS)"
                        : " (login as " + account.nick() + " failed: " + authProblem + ")";
            }
            throw new IOException("Cannot join " + channel + ": " + wait.error + hint);
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
        identified.countDown();
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
            identified.countDown();
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
                    // with an account, SASL/IDENTIFY still log in to it under another nick
                    nick = account != null && "433".equals(m.command())
                            ? account.nick() + "_" + ThreadLocalRandom.current().nextInt(100, 999)
                            : randomNick();
                    send("NICK " + nick);
                }
            }
            case "CAP" -> handleCap(m);
            case "AUTHENTICATE" -> {
                if ("+".equals(m.param(0)) && account != null) {
                    String plain = account.nick() + "\0" + account.nick() + "\0" + account.password();
                    send("AUTHENTICATE " + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
                }
            }
            case "900" -> { // RPL_LOGGEDIN
                loggedIn = true;
                identified.countDown();
            }
            case "903" -> { // SASL success
                loggedIn = true;
                identified.countDown();
                capEnd();
            }
            case "902", "904", "905", "906" -> { // SASL failed: fall back to NickServ after registration
                log.debug("IRC: SASL login to {} failed: {}", server(), m.text());
                capEnd();
            }
            case "NOTICE" -> {
                if ("NickServ".equalsIgnoreCase(m.nick()) && account != null && m.text() != null) {
                    handleNickServ(IrcMessage.stripFormatting(m.text()).strip());
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
                        w.code = m.command();
                        w.done.countDown();
                    }
                }
            }
        }
    }

    private void handleCap(IrcMessage m) throws IOException {
        String sub = m.param(1);
        String caps = m.text() == null ? "" : m.text().toLowerCase(Locale.ROOT);
        if ("ACK".equalsIgnoreCase(sub) && caps.contains("sasl")) {
            send("AUTHENTICATE PLAIN");
        } else if ("NAK".equalsIgnoreCase(sub)) {
            capEnd();
        }
    }

    private void capEnd() throws IOException {
        if (!capEnded) {
            capEnded = true;
            send("CAP END");
        }
    }

    /** Anope/Atheme style NickServ replies to IDENTIFY and REGISTER. */
    private void handleNickServ(String text) throws IOException {
        if (loggedIn) {
            return;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("password accepted") || t.contains("you are now identified") || t.contains("you are now logged in")) {
            loggedIn = true;
            identified.countDown();
        } else if (t.contains("password incorrect") || t.contains("invalid password") || t.contains("incorrect password")) {
            authProblem = text;
            identified.countDown();
        } else if (t.contains("passcode") || t.contains("confirm") && registerSent) {
            authProblem = "NickServ wants the account confirmed (see the e-mail sent to "
                    + account.registerEmail() + "): " + text;
            identified.countDown();
        } else if (t.contains("not registered") || t.contains("isn't registered") || t.contains("is not a registered")) {
            boolean ownNick = account.nick().equalsIgnoreCase(nick);
            if (!ownNick && !t.contains(account.nick().toLowerCase(Locale.ROOT))) {
                return; // about the stand-in nick we got because ours was taken
            }
            if (account.registerEmail() != null && !registerSent && ownNick && registered.getCount() == 0) {
                registerSent = true;
                log.info("IRC: registering nick {} on {}", account.nick(), server());
                send("PRIVMSG NickServ :REGISTER " + account.password() + " " + account.registerEmail());
            } else if (!registerSent) {
                authProblem = account.registerEmail() == null
                        ? "nick " + account.nick() + " is not registered (register it, or set XDCC_IRC_REGISTER_EMAIL)"
                        : text;
                identified.countDown();
            }
        } else if (registerSent && (t.contains("denied") || t.contains("cannot") || t.contains("too many")
                || t.contains("must") || t.contains("invalid"))) {
            authProblem = "NickServ refused to register " + account.nick() + ": " + text;
            identified.countDown();
        }
    }

    private String randomNick() {
        return nickPrefix + ThreadLocalRandom.current().nextInt(10_000, 99_999);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
