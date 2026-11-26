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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IrcClientAuthTest {

    /** Anope-like services on an UnrealIRCd-like server; "#reg" needs a registered nick. */
    static final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile boolean sasl = true;
        volatile String registeredNick = "Me";
        volatile String password = "secret";
        volatile boolean confirmByMail;
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
                                if (userSeen) {
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
                        case "NICK" -> nick = m.param(0);
                        case "USER" -> {
                            userSeen = true;
                            if (!capPending) {
                                welcome();
                            }
                        }
                        case "PRIVMSG" -> {
                            if (!"NickServ".equalsIgnoreCase(m.param(0))) {
                                break;
                            }
                            String[] w = m.text().split(" ");
                            if (w[0].equalsIgnoreCase("IDENTIFY")) {
                                if (!w[1].equals(registeredNick)) {
                                    nickServ("Nick \u0002" + w[1] + "\u0002 isn't registered.");
                                } else if (w[2].equals(password)) {
                                    nickServ("Password accepted - you are now recognized.");
                                    login();
                                } else {
                                    nickServ("Password incorrect.");
                                }
                            } else if (w[0].equalsIgnoreCase("REGISTER")) {
                                registeredNick = nick;
                                password = w[1];
                                nickServ("Nickname \u0002" + nick + "\u0002 registered.");
                                if (confirmByMail) {
                                    nickServ("A passcode has been sent to " + w[2] + ", please type /msg NickServ CONFIRM <passcode> to confirm your email address.");
                                } else {
                                    login();
                                }
                            }
                        }
                        case "JOIN" -> {
                            String ch = m.param(0);
                            if (ch.equals("#reg") && !loggedIn) {
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

    Server server;
    IrcClient client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private IrcClient connect(IrcAccount account) throws IOException {
        server = server == null ? new Server() : server;
        client = new IrcClient("127.0.0.1", server.port(), "HoS-", account);
        client.connect(Duration.ofSeconds(10));
        return client;
    }

    private static IrcAccount account(String nick, String password, String email) {
        return new IrcAccount("127.0.0.1", nick, password, email);
    }

    @Test
    void logsInWithSaslAndJoinsRegisteredOnlyChannel() throws IOException {
        IrcClient c = connect(account("Me", "secret", null));
        assertThat(c.isLoggedIn()).isTrue();
        assertThat(c.nick()).isEqualTo("Me");
        c.join("#reg", Duration.ofSeconds(5));
        assertThat(c.isIn("#reg")).isTrue();
        assertThat(server.received).noneMatch(l -> l.contains("IDENTIFY"));
    }

    @Test
    void fallsBackToNickServIdentifyWithoutSasl() throws IOException {
        server = new Server();
        server.sasl = false;
        IrcClient c = connect(account("Me", "secret", null));
        assertThat(c.isLoggedIn()).isTrue();
        assertThat(server.received).contains("PRIVMSG NickServ :IDENTIFY Me secret");
        c.join("#reg", Duration.ofSeconds(5));
    }

    @Test
    void wrongPasswordIsReportedOnJoin() throws IOException {
        IrcClient c = connect(account("Me", "wrong", null));
        assertThat(c.isLoggedIn()).isFalse();
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("registered nick")
                .hasMessageContaining("login as Me failed: Password incorrect");
    }

    @Test
    void registersNickWhenEmailGiven() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        IrcClient c = connect(account("New", "pw12345", "me@example.org"));
        assertThat(server.received).contains("PRIVMSG NickServ :REGISTER pw12345 me@example.org");
        assertThat(c.isLoggedIn()).isTrue();
        c.join("#reg", Duration.ofSeconds(5));
    }

    @Test
    void reportsPendingMailConfirmation() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        server.confirmByMail = true;
        IrcClient c = connect(account("New", "pw12345", "me@example.org"));
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("confirmed")
                .hasMessageContaining("me@example.org");
    }

    @Test
    void unregisteredNickWithoutEmailIsNotRegistered() throws IOException {
        server = new Server();
        server.registeredNick = "Someone";
        IrcClient c = connect(account("New", "pw12345", null));
        assertThat(server.received).noneMatch(l -> l.contains("REGISTER"));
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("not registered");
    }

    @Test
    void withoutAccountTheJoinErrorSaysWhatToConfigure() throws IOException {
        IrcClient c = connect(null);
        assertThat(server.received).noneMatch(l -> l.startsWith("CAP"));
        assertThatThrownBy(() -> c.join("#reg", Duration.ofSeconds(5)))
                .hasMessageContaining("XDCC_IRC_ACCOUNTS");
    }

    @Test
    void parsesAccountsAndPicksTheMostSpecific() {
        List<IrcAccount> a = IrcAccount.parse("scenep2p.net=Me:pa:ss, irc.scenep2p.net=Other:x1", "m@x.org");
        assertThat(a).hasSize(2);
        assertThat(a.get(0).password()).isEqualTo("pa:ss");
        assertThat(a.get(0).registerEmail()).isEqualTo("m@x.org");
        assertThat(IrcAccount.forHost(a, "IRC.SceneP2P.net")).map(IrcAccount::nick).contains("Other");
        assertThat(IrcAccount.forHost(a, "eu.scenep2p.net")).map(IrcAccount::nick).contains("Me");
        assertThat(IrcAccount.forHost(a, "irc.rizon.net")).isEmpty();
        assertThat(a.get(0).toString()).doesNotContain("pa:ss");
        assertThatThrownBy(() -> IrcAccount.parse("bad", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
