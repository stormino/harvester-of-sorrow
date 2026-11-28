package com.github.stormino.service.link.xdcc;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal IRC client: registration (with a random nick, retried when taken), PING/PONG, joining
 * channels, tracking who is in them, and sending messages. Everything received is also handed to
 * the registered listeners (for XDCC: bot notices and CTCP DCC offers).
 *
 * <p>Registered nick: when a channel answers "You need a registered nick" (477), the client takes the
 * configured {@link IrcAccount}'s nick on the same connection, logs in with NickServ (registering the
 * nick first if allowed) and joins again. On networks known to need it, it logs in while connecting
 * instead (SASL PLAIN, else NickServ).
 */
@Slf4j
public final class IrcClient implements Closeable {

    /** Numerics that refuse a JOIN: no such channel, full, invite-only, banned, bad key, needs registration, ... */
    private static final Set<String> JOIN_ERRORS = Set.of("403", "405", "471", "473", "474", "475", "477", "489", "520");
    private static final String NEEDS_REGISTERED_NICK = "477";
    /** Anope: "You must have been using this nick for at least 30 seconds to register." */
    private static final Pattern REGISTER_TOO_SOON = Pattern.compile("at least (\\d+) seconds?");
    /** Longest wait before registering again that we accept (a download is waiting meanwhile). */
    private static final int MAX_REGISTER_WAIT_SECONDS = 120;
    /** Standard IRC-over-TLS port, tried before the link's (plain) port. */
    static final int TLS_PORT = 6697;
    /** Addresses of a round-robin host tried over TLS before falling back to plain IRC. */
    private static final int TLS_ADDRESS_ATTEMPTS = 3;
    private static final int TLS_CONNECT_MILLIS = 6_000;
    private static final int PLAIN_CONNECT_MILLIS = 8_000;

    /** Where the client stands with the registered nick, for the Settings screen. */
    public enum AuthState {
        /** Random nick; no channel asked for more. */
        ANONYMOUS,
        LOGGING_IN,
        LOGGED_IN,
        /** NickServ mailed a code that must be confirmed. */
        NEEDS_CONFIRM,
        FAILED,
        /** A channel needs a registered nick but none is configured. */
        NO_ACCOUNT
    }

    /** Told about every login state change, and when a channel turned out to need a registered nick. */
    public interface AuthListener {
        void authState(IrcClient client, AuthState state, String message);

        default void needsAccount(IrcClient client) {
        }
    }

    private final String host;
    private final int port;
    private final String nickPrefix;
    private final Supplier<IrcAccount> accounts;
    private final boolean loginOnConnect;
    private final boolean tlsEnabled;
    private final AuthListener authListener;
    private final List<Consumer<IrcMessage>> listeners = new CopyOnWriteArrayList<>();
    /** channel (lower case) -> nicks (lower case) */
    private final Map<String, Set<String>> members = new ConcurrentHashMap<>();
    private final Map<String, JoinWait> joins = new ConcurrentHashMap<>();
    private final Object loginLock = new Object();

    private Socket socket;
    private volatile boolean tls;
    private OutputStream out;
    private volatile String nick;
    private volatile boolean closed;
    private volatile long lastActivity = System.nanoTime();
    private final CountDownLatch registered = new CountDownLatch(1);

    /** Account being logged in to / logged in to; null while anonymous. */
    private volatile IrcAccount account;
    /** Counted down once logged in, or once that has clearly failed; replaced per attempt. */
    private volatile CountDownLatch identified = new CountDownLatch(1);
    /** Counted down when the server accepts or refuses our nick change. */
    private volatile CountDownLatch nickChanged = new CountDownLatch(1);
    private volatile CountDownLatch confirmed = new CountDownLatch(1);
    /** Counted down when NickServ asks for the mailed code after REGISTER. */
    private volatile CountDownLatch passcode = new CountDownLatch(1);
    private volatile boolean loggedIn;
    private volatile boolean capEnded;
    private volatile boolean registerSent;
    private volatile boolean confirmSent;
    private volatile boolean confirmOk;
    private volatile String authProblem;
    /** Seconds NickServ wants us to hold the nick before registering it; 0 = no such request. */
    private volatile int registerWaitSeconds;
    private volatile AuthState authState = AuthState.ANONYMOUS;

    private static final class JoinWait {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String error;
        volatile String code;
    }

    public IrcClient(String host, int port, String nickPrefix) {
        this(host, port, nickPrefix, () -> null, false, null, true);
    }

    public IrcClient(String host, int port, String nickPrefix, Supplier<IrcAccount> accounts,
                     boolean loginOnConnect, AuthListener authListener) {
        this(host, port, nickPrefix, accounts, loginOnConnect, authListener, true);
    }

    /**
     * @param accounts       the registered nick to use when a channel needs one (null result: none configured)
     * @param loginOnConnect log in while connecting (the network is known to need it)
     * @param authListener   told about login state changes; may be null
     * @param tlsEnabled     try TLS on 6697 before plain IRC
     */
    public IrcClient(String host, int port, String nickPrefix, Supplier<IrcAccount> accounts,
                     boolean loginOnConnect, AuthListener authListener, boolean tlsEnabled) {
        this.host = host;
        this.port = port;
        this.nickPrefix = nickPrefix;
        this.accounts = accounts;
        this.loginOnConnect = loginOnConnect;
        this.authListener = authListener;
        this.tlsEnabled = tlsEnabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String server() {
        return host + ":" + port;
    }

    public String nick() {
        return nick;
    }

    /** Whether we are logged in to a services account (registered nick). */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    public AuthState authState() {
        return authState;
    }

    /** Last login problem, or null. */
    public String authProblem() {
        return authProblem;
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

    /** Connects and registers; returns once the server has welcomed us (and we logged in, if asked to). */
    public void connect(Duration timeout) throws IOException {
        IrcAccount acc = loginOnConnect ? accounts.get() : null;
        socket = open();
        socket.setKeepAlive(true);
        out = socket.getOutputStream();
        account = acc;
        nick = acc != null ? acc.nick() : randomNick();
        Thread reader = new Thread(this::readLoop, "irc-" + host);
        reader.setDaemon(true);
        reader.start();
        if (acc != null) {
            setState(AuthState.LOGGING_IN, null);
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
        log.info("IRC: connected to {} as {}{}", server(), nick,
                tls ? " (TLS, " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + ")"
                        : " (" + socket.getInetAddress().getHostAddress() + ")");
        if (acc != null) {
            synchronized (loginLock) {
                finishLogin(acc, Duration.ofSeconds(30));
            }
        }
    }

    /** Whether the connection is encrypted (TLS on {@link #TLS_PORT}). */
    public boolean isTls() {
        return tls;
    }

    /**
     * Opens the connection: TLS on 6697 first (certificate checked), else plain IRC on {@link #port}.
     * Networks often publish many servers under one name, some of them down or plain IRC firewalled,
     * so every address is tried in turn with a short timeout.
     */
    private Socket open() throws IOException {
        List<InetAddress> addresses = Arrays.stream(InetAddress.getAllByName(host))
                .sorted(Comparator.comparing(a -> a instanceof Inet4Address ? 0 : 1)) // stable: keeps DNS order
                .toList();
        IOException last = null;
        if (tlsEnabled && port != TLS_PORT) {
            for (InetAddress a : addresses.subList(0, Math.min(TLS_ADDRESS_ATTEMPTS, addresses.size()))) {
                try {
                    Socket s = openTls(a, TLS_PORT);
                    tls = true;
                    return s;
                } catch (IOException e) {
                    log.debug("IRC: TLS to {} ({}:{}) failed: {}", host, a.getHostAddress(), TLS_PORT, e.getMessage());
                    last = e;
                }
            }
        }
        for (InetAddress a : addresses) {
            try {
                if (port == TLS_PORT && tlsEnabled) {
                    Socket s = openTls(a, port);
                    tls = true;
                    return s;
                }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a, port), PLAIN_CONNECT_MILLIS);
                } catch (IOException e) {
                    s.close();
                    throw e;
                }
                return s;
            } catch (IOException e) {
                log.debug("IRC: {} ({}:{}) failed: {}", host, a.getHostAddress(), port, e.getMessage());
                last = e;
            }
        }
        throw new IOException(addresses.size() > 1
                ? "none of the " + addresses.size() + " servers answered (" + (last == null ? "?" : last.getMessage()) + ")"
                : last == null ? "no address" : last.getMessage());
    }

    private Socket openTls(InetAddress address, int tlsPort) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, tlsPort), TLS_CONNECT_MILLIS);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, tlsPort, true);
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // certificate must match the host name
            s.setSSLParameters(params);
            raw.setSoTimeout(TLS_CONNECT_MILLIS);
            s.startHandshake();
            raw.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            raw.close();
            throw e;
        }
    }

    /**
     * Joins {@code channel} (no-op if already in it) and waits for the member list. If the channel
     * needs a registered nick, logs in with the configured one and tries once more.
     */
    public void join(String channel, Duration timeout) throws IOException {
        JoinWait first = tryJoin(channel, timeout);
        if (first == null) {
            return;
        }
        if (NEEDS_REGISTERED_NICK.equals(first.code)) {
            if (authListener != null) {
                authListener.needsAccount(this);
            }
            if (!loggedIn) {
                login();
            }
            if (loggedIn && authState != AuthState.NEEDS_CONFIRM) {
                JoinWait again = tryJoin(channel, timeout);
                if (again == null) {
                    return;
                }
                first = again;
            }
            throw new IOException("Cannot join " + channel + ": " + first.error + loginHint());
        }
        throw new IOException("Cannot join " + channel + ": " + first.error);
    }

    private String loginHint() {
        return switch (authState) {
            case NO_ACCOUNT -> " (set the IRC account in Settings)";
            case NEEDS_CONFIRM -> " (" + authProblem + ": enter it in Settings > IRC Account)";
            case LOGGED_IN -> "";
            default -> authProblem == null ? "" : " (login as " + account.nick() + " failed: " + authProblem + ")";
        };
    }

    /** Null when joined, else the refusal. */
    private JoinWait tryJoin(String channel, Duration timeout) throws IOException {
        String key = channel.toLowerCase(Locale.ROOT);
        if (members.containsKey(key)) {
            return null;
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
        return wait.error == null ? null : wait;
    }

    /** Takes the configured nick on this connection and logs in to it (registering it if allowed). */
    private void login() throws IOException {
        synchronized (loginLock) {
            if (loggedIn) {
                return;
            }
            IrcAccount acc = accounts.get();
            if (acc == null) {
                setState(AuthState.NO_ACCOUNT, "a channel needs a registered nick; none is set");
                return;
            }
            account = acc;
            authProblem = null;
            registerSent = false;
            registerWaitSeconds = 0;
            identified = new CountDownLatch(1);
            passcode = new CountDownLatch(1);
            setState(AuthState.LOGGING_IN, null);
            if (!acc.nick().equalsIgnoreCase(nick)) {
                nickChanged = new CountDownLatch(1);
                send("NICK " + acc.nick());
                await(nickChanged, Duration.ofSeconds(10));
                // nick taken (e.g. our own ghost): IDENTIFY <account> still logs in under the stand-in nick
            }
            finishLogin(acc, Duration.ofSeconds(30));
        }
    }

    /** IDENTIFY unless SASL already logged us in, then wait for the outcome. Caller holds loginLock. */
    private void finishLogin(IrcAccount acc, Duration timeout) throws IOException {
        if (!loggedIn) {
            send("PRIVMSG NickServ :IDENTIFY " + acc.nick() + " " + acc.password());
        }
        if (!await(identified, timeout) && authProblem == null && !loggedIn) {
            authProblem = "no answer from NickServ";
        }
        int wait = registerWaitSeconds;
        if (wait > 0 && !loggedIn && wait <= MAX_REGISTER_WAIT_SECONDS) {
            registerWaitSeconds = 0;
            log.info("IRC: NickServ on {} wants {} held for {}s before registering it; waiting", server(), acc.nick(), wait);
            sleep(Duration.ofSeconds(wait + 3));
            authProblem = null;
            identified = new CountDownLatch(1);
            passcode = new CountDownLatch(1);
            log.info("IRC: registering nick {} on {} (again)", acc.nick(), server());
            send("PRIVMSG NickServ :REGISTER " + acc.password() + " " + acc.registerEmail());
            if (!await(identified, timeout) && authProblem == null && !loggedIn) {
                authProblem = "no answer from NickServ";
            }
        }
        if (registerSent && authState != AuthState.NEEDS_CONFIRM) {
            await(passcode, Duration.ofSeconds(3)); // the login may come before "a passcode has been sent"
        }
        if (authState == AuthState.NEEDS_CONFIRM) {
            log.warn("IRC: {} on {} needs e-mail confirmation", acc.nick(), server());
        } else if (loggedIn) {
            log.info("IRC: logged in to {} as {}", server(), acc.nick());
            setState(AuthState.LOGGED_IN, null);
        } else {
            log.warn("IRC: could not log in to {} as {}: {}", server(), acc.nick(), authProblem);
            setState(AuthState.FAILED, authProblem);
        }
    }

    /**
     * Sends the code NickServ mailed after registering ({@code CONFIRM <code>}), then logs in again.
     *
     * @throws IOException when NickServ refuses the code or does not answer
     */
    public void confirm(String code) throws IOException {
        synchronized (loginLock) {
            IrcAccount acc = account != null ? account : accounts.get();
            if (acc == null) {
                throw new IOException("No IRC account is set");
            }
            if (account == null || !loggedIn && authState != AuthState.NEEDS_CONFIRM) {
                account = acc;
                if (!acc.nick().equalsIgnoreCase(nick)) {
                    nickChanged = new CountDownLatch(1);
                    send("NICK " + acc.nick());
                    await(nickChanged, Duration.ofSeconds(10));
                }
                identified = new CountDownLatch(1);
                send("PRIVMSG NickServ :IDENTIFY " + acc.nick() + " " + acc.password());
                await(identified, Duration.ofSeconds(15));
            }
            confirmed = new CountDownLatch(1);
            confirmOk = false;
            confirmSent = true;
            send("PRIVMSG NickServ :CONFIRM " + code.strip());
            boolean answered = await(confirmed, Duration.ofSeconds(20));
            confirmSent = false;
            if (!answered) {
                throw new IOException("No answer from NickServ");
            }
            if (!confirmOk) {
                throw new IOException(authProblem);
            }
            log.info("IRC: {} confirmed on {}", acc.nick(), server());
            authProblem = null;
            registerSent = false;
            loggedIn = false;
            identified = new CountDownLatch(1);
            setState(AuthState.LOGGING_IN, null);
            finishLogin(acc, Duration.ofSeconds(20));
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
        releaseWaiters();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private void releaseWaiters() {
        registered.countDown();
        identified.countDown();
        nickChanged.countDown();
        confirmed.countDown();
        passcode.countDown();
        joins.values().forEach(w -> {
            w.error = "disconnected";
            w.done.countDown();
        });
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
            releaseWaiters();
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
            case "433", "432", "437" -> { // nick in use / erroneous / unavailable
                if (registered.getCount() > 0) {
                    // with an account, SASL/IDENTIFY still log in to it under another nick
                    nick = account != null && !"432".equals(m.command())
                            ? account.nick() + "_" + ThreadLocalRandom.current().nextInt(100, 999)
                            : randomNick();
                    send("NICK " + nick);
                } else {
                    nickChanged.countDown(); // keep the current nick
                }
            }
            case "CAP" -> handleCap(m);
            case "AUTHENTICATE" -> {
                IrcAccount acc = account;
                if ("+".equals(m.param(0)) && acc != null) {
                    String plain = acc.nick() + "\0" + acc.nick() + "\0" + acc.password();
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
            case "902", "904", "905", "906" -> { // SASL failed: NickServ IDENTIFY after registration
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
                    nickChanged.countDown();
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

    /** Anope/Atheme style NickServ replies to IDENTIFY, REGISTER and CONFIRM. */
    private void handleNickServ(String text) throws IOException {
        IrcAccount acc = account;
        String t = text.toLowerCase(Locale.ROOT);
        Matcher m;
        if (confirmSent) {
            if (t.contains("confirmed") || t.contains("verified")) {
                confirmOk = true;
                confirmed.countDown();
            } else if (t.contains("invalid") || t.contains("incorrect") || t.contains("wrong") || t.contains("unknown")) {
                authProblem = "NickServ refused the code: " + text;
                confirmed.countDown();
            }
            return;
        }
        if (t.contains("passcode") || registerSent && t.contains("confirm")) {
            authProblem = "NickServ sent a code to " + acc.registerEmail() + " to confirm the nick";
            setState(AuthState.NEEDS_CONFIRM, authProblem);
            identified.countDown();
            passcode.countDown();
            return;
        }
        if (loggedIn) {
            return;
        }
        if (t.contains("password accepted") || t.contains("you are now identified") || t.contains("you are now logged in")) {
            loggedIn = true;
            identified.countDown();
        } else if (t.contains("password incorrect") || t.contains("invalid password") || t.contains("incorrect password")) {
            authProblem = "wrong password";
            identified.countDown();
        } else if (t.contains("not registered") || t.contains("isn't registered") || t.contains("is not a registered")) {
            boolean ownNick = acc.nick().equalsIgnoreCase(nick);
            if (!ownNick && !t.contains(acc.nick().toLowerCase(Locale.ROOT))) {
                return; // about the stand-in nick we got because ours was taken
            }
            if (acc.registerEmail() != null && !registerSent && ownNick && registered.getCount() == 0) {
                registerSent = true;
                log.info("IRC: registering nick {} on {}", acc.nick(), server());
                send("PRIVMSG NickServ :REGISTER " + acc.password() + " " + acc.registerEmail());
            } else if (!registerSent) {
                authProblem = acc.registerEmail() == null
                        ? "nick " + acc.nick() + " is not registered here (turn on automatic registration)"
                        : "nick " + acc.nick() + " is not registered here";
                identified.countDown();
            }
        } else if (registerSent && (m = REGISTER_TOO_SOON.matcher(t)).find()) {
            registerWaitSeconds = Integer.parseInt(m.group(1));
            authProblem = "NickServ refused to register " + acc.nick() + ": " + text;
            identified.countDown();
        } else if (registerSent && (t.contains("denied") || t.contains("cannot") || t.contains("too many")
                || t.contains("must") || t.contains("invalid"))) {
            authProblem = "NickServ refused to register " + acc.nick() + ": " + text;
            identified.countDown();
        }
    }

    private void setState(AuthState state, String message) {
        authState = state;
        if (authListener != null) {
            try {
                authListener.authState(this, state, message);
            } catch (RuntimeException e) {
                log.debug("IRC auth listener failed: {}", e.getMessage());
            }
        }
    }

    private static boolean await(CountDownLatch latch, Duration timeout) throws IOException {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private static void sleep(Duration d) throws IOException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private String randomNick() {
        return nickPrefix + ThreadLocalRandom.current().nextInt(10_000, 99_999);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
