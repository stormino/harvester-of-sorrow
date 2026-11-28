package com.github.stormino.service.link.xdcc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IrcClientAuthTest {

    /** Anope-like services on an UnrealIRCd-like server; "#reg" needs a registered (and confirmed) nick. */
    static final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile boolean sasl = true;
        volatile String registeredNick = "Me";
        volatile String password = "secret";
        volatile boolean confirmByMail;
        volatile boolean confirmed = true;
        volatile Set<String> takenNicks = Set.of();
        /** Anope's "you must have been using this nick for at least N seconds to register". */
        volatile int registerMinSeconds;
        private volatile long nickSince = System.nanoTime();
        private PrintWriter out;
        private String nick;
        private boolean loggedIn;
        private boolean capPending;

        Server() throws IOException {
            Thread t = new Thread(this::serve, "fake-irc-auth");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void send(String l) {
            out.print(l + "\r\n");
            out.flush();
        }

        private void nickServ(String text) {
            send(":NickServ!services@services NOTICE " + nick + " :" + text);
        }

        private void login() {
            loggedIn = true;
            send(":srv 900 " + nick + " " + nick + "!u@h " + registeredNick + " :You are now logged in as " + registeredNick);
        }

        private void serve() {
            try (Socket s = socket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
                String line;
                boolean welcomed = false;
                boolean userSeen = false;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    IrcMessage m = IrcMessage.parse(line);
                    switch (m.command()) {
                        case "CAP" -> {
                            if ("REQ".equals(m.param(0))) {
                                capPending = true;
                                send(":srv CAP * " + (sasl ? "ACK" : "NAK") + " :sasl");
                            } else if ("END".equals(m.param(0))) {
                                capPending = false;
                                if (userSeen && !welcomed) {
                                    welcomed = true;
                                    welcome();
                                }
                            }
                        }
                        case "AUTHENTICATE" -> {
                            if ("PLAIN".equals(m.param(0))) {
                                send("AUTHENTICATE +");
                            } else {
                                String[] p = new String(Base64.getDecoder().decode(m.param(0)), StandardCharsets.UTF_8).split("\0");
                                if (p[1].equals(registeredNick) && p[2].equals(password)) {
                                    login();
                                    send(":srv 903 * :SASL authentication successful");
                                } else {
                                    send(":srv 904 * :SASL authentication failed");
                                }
                            }
                        }
                        case "NICK" -> {
                            String wanted = m.param(0);
                            if (!welcomed) {
                                nick = wanted;
                            } else if (takenNicks.contains(wanted)) {
                                send(":srv 433 " + nick + " " + wanted + " :Nickname is already in use.");
                            } else {
                                send(":" + nick + "!u@h NICK :" + wanted);
                                nick = wanted;
                                nickSince = System.nanoTime();
                            }
                        }
                        case "USER" -> {
                            userSeen = true;
                            if (!capPending) {
                                welcomed = true;
                                welcome();
                            }
                        }
                        case "PRIVMSG" -> {
                            if ("NickServ".equalsIgnoreCase(m.param(0))) {
                                nickServ(m.text().split(" "));
                            }
                        }
                        case "JOIN" -> {
                            String ch = m.param(0);
                            if (ch.equals("#reg") && !(loggedIn && confirmed)) {
                                send(":srv 477 " + nick + " " + ch + " :You need a registered nick to join that channel.");
                            } else {
                                send(":" + nick + "!u@h JOIN :" + ch);
                                send(":srv 353 " + nick + " = " + ch + " :" + nick);
                                send(":srv 366 " + nick + " " + ch + " :End of /NAMES list.");
                            }
                        }
                        case "QUIT" -> {
                            return;
                        }
                        default -> {
                        }
                    }
                }
            } catch (IOException ignored) {
                // closed
            }
        }

        private void nickServ(String[] w) {
            switch (w[0].toUpperCase()) {
                case "IDENTIFY" -> {
                    if (!w[1].equals(registeredNick)) {
                        nickServ("Nick \u0002" + w[1] + "\u0002 isn't registered.");
                    } else if (w[2].equals(password)) {
                        nickServ("Password accepted - you are now recognized.");
                        login();
                    } else {
                        nickServ("Password incorrect.");
                    }
                }
                case "REGISTER" -> {
                    if (System.nanoTime() - nickSince < registerMinSeconds * 1_000_000_000L) {
                        nickServ("You must have been using this nick for at least " + registerMinSeconds
                                + " seconds to register.");
                        return;
                    }
                    registeredNick = nick;
                    password = w[1];
                    nickServ("Nickname \u0002" + nick + "\u0002 registered.");
                    if (confirmByMail) {
                        confirmed = false;
                        login();
                        nickServ("A passcode has been sent to " + w[2] + ", please type /msg NickServ CONFIRM <passcode> to confirm your email address.");
                    } else {
                        login();
                    }
                }
                case "CONFIRM" -> {
                    if (w[1].equals("1234")) {
                        confirmed = true;
                        nickServ("Your email address of \u0002x\u0002 has been confirmed.");
                    } else {
                        nickServ("Invalid passcode.");
                    }
                }
                default -> {
                }
            }
        }

        private void welcome() {
            send(":srv 001 " + nick + " :Welcome");
            if (!loggedIn && !nick.equals(registeredNick)) {
                nickServ("Your nickname is not registered. To register it, use: /msg NickServ REGISTER password email");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    record Event(IrcClient.AuthState state, String message) {
    }

    Server server = null;
    IrcClient client;
    final List<Event> events = new CopyOnWriteArrayList<>();
    final List<String> needsAccount = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private IrcClient connect(IrcAccount account, boolean loginOnConnect) throws IOException {
        server = server == null ? new Server() : server;
        client = new IrcClient("127.0.0.1", server.port(), "HoS-", () -> account, loginOnConnect,
                new IrcClient.AuthListener() {
                    @Override
                    public void authState(IrcClient c, IrcClient.AuthState state, String message) {
                        events.add(new Event(state, message));
                    }

                    @Override
                    public void needsAccount(IrcClient c) {
                        needsAccount.add(c.host());
                    }
                });
        client.connect(Duration.ofSeconds(10));
        return client;
    }

    @Test
    void staysAnonymousUntilAChannelNeedsTheNick() throws IOException {
        IrcClient c = connect(new IrcAccount("Me", "secret", null), false);
        assertThat(c.nick()).startsWith("HoS-");
        c.join("#open", Duration.ofSeconds(5));
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.ANONYMOUS);
        assertThat(server.received).noneMatch(l -> l.contains("NickServ") || l.startsWith("CAP"));
    }

    @Test
    void takesTheNickAndLogsInWhenAChannelNeedsIt() throws IOException {
        IrcClient c = connect(new IrcAccount("Me", "secret", null), false);
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(c.isIn("#reg")).isTrue();
        assertThat(c.nick()).isEqualTo("Me");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.LOGGED_IN);
        assertThat(server.received).contains("NICK Me", "PRIVMSG NickServ :IDENTIFY Me secret");
        assertThat(needsAccount).containsExactly("127.0.0.1");
        assertThat(events).extracting(Event::state).endsWith(IrcClient.AuthState.LOGGED_IN);
    }

    @Test
    void logsInWhileConnectingWithSaslWhereKnownToBeNeeded() throws IOException {
        IrcClient c = connect(new IrcAccount("Me", "secret", null), true);
        assertThat(c.isLoggedIn()).isTrue();
        assertThat(c.nick()).isEqualTo("Me");
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(server.received).noneMatch(l -> l.contains("IDENTIFY"));
    }

    @Test
    void fallsBackToIdentifyWithoutSasl() throws IOException {
        server = new Server();
        server.sasl = false;
        IrcClient c = connect(new IrcAccount("Me", "secret", null), true);
        assertThat(c.isLoggedIn()).isTrue();
        assertThat(server.received).contains("PRIVMSG NickServ :IDENTIFY Me secret");
    }

    @Test
    void logsInUnderAStandInNickWhenOursIsTaken() throws IOException {
        server = new Server();
        server.takenNicks = Set.of("Me");
        IrcClient c = connect(new IrcAccount("Me", "secret", null), false);
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(c.nick()).startsWith("HoS-");
        assertThat(c.isLoggedIn()).isTrue();
    }

    @Test
    void wrongPasswordIsReportedOnJoin() throws IOException {
        IrcClient c = connect(new IrcAccount("Me", "wrong", null), false);
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("registered nick")
                .hasMessageContaining("login as Me failed: wrong password");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.FAILED);
    }

    @Test
    void registersTheNickWhenAllowed() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        IrcClient c = connect(new IrcAccount("New", "pw12345", "me@example.org"), false);
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(server.received).contains("PRIVMSG NickServ :REGISTER pw12345 me@example.org");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.LOGGED_IN);
    }

    @Test
    void waitsWhenNickServWantsTheNickHeldBeforeRegistering() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        server.registerMinSeconds = 1;
        IrcClient c = connect(new IrcAccount("New", "pw12345", "me@example.org"), false);
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(server.received.stream().filter(l -> l.startsWith("PRIVMSG NickServ :REGISTER"))).hasSize(2);
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.LOGGED_IN);
        assertThat(c.isIn("#reg")).isTrue();
    }

    @Test
    void unregisteredNickIsNotRegisteredWithoutPermission() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        IrcClient c = connect(new IrcAccount("New", "pw12345", null), false);
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("not registered here");
        assertThat(server.received).noneMatch(l -> l.contains("REGISTER"));
    }

    @Test
    void mailedCodeIsAskedForThenConfirmed() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        server.confirmByMail = true;
        IrcClient c = connect(new IrcAccount("New", "pw12345", "me@example.org"), false);
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("me@example.org")
                .hasMessageContaining("Settings");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.NEEDS_CONFIRM);

        assertThatThrownBy(() -> c.confirm("9999")).hasMessageContaining("refused the code");

        c.confirm("1234");
        assertThat(server.received).contains("PRIVMSG NickServ :CONFIRM 1234");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.LOGGED_IN);
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(c.isIn("#reg")).isTrue();
    }

    @Test
    void withoutAnAccountTheErrorPointsToSettings() throws IOException {
        IrcClient c = connect(null, false);
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("Settings");
        assertThat(c.authState()).isEqualTo(IrcClient.AuthState.NO_ACCOUNT);
        assertThat(needsAccount).hasSize(1);
    }

    @Test
    void validatesAccountValues() {
        assertThat(IrcAccount.validate("MyNick", "secret", null, false)).isNull();
        assertThat(IrcAccount.validate("MyNick", "secret", "me@example.org", true)).isNull();
        assertThat(IrcAccount.validate("1abc", "secret", null, false)).startsWith("Nick");
        assertThat(IrcAccount.validate("MyNick", "abc", null, false)).startsWith("Password");
        assertThat(IrcAccount.validate("MyNick", "has space", null, false)).startsWith("Password");
        assertThat(IrcAccount.validate("MyNick", "mynick", null, false)).contains("not be the nick");
        assertThat(IrcAccount.validate("MyNick", "secret", null, true)).startsWith("E-mail");
        assertThat(new IrcAccount("MyNick", "secret", null).toString()).doesNotContain("secret");
    }
}
