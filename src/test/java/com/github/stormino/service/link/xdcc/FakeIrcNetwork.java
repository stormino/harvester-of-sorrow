package com.github.stormino.service.link.xdcc;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An IRC server with one channel and one XDCC bot ("Bot" in "#chan"), for tests. The bot's
 * behaviour on "xdcc send #N" is scripted via {@link #onRequest}.
 */
final class FakeIrcNetwork implements Closeable {

    interface Behaviour {
        void request(FakeIrcNetwork net, String clientNick, int pack) throws IOException;
    }

    final ServerSocket server;
    final List<String> received = new CopyOnWriteArrayList<>();
    volatile Behaviour onRequest = FakeIrcNetwork::sendFile;
    volatile boolean botInChannel = true;
    volatile byte[] file = new byte[0];
    volatile String fileName = "file.mkv";
    volatile long acceptResumeAt = -1; // >= 0: answer DCC RESUME with ACCEPT at this position
    private volatile PrintWriter out;
    private volatile String clientNick;
    private ServerSocket dcc;

    FakeIrcNetwork() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::serve, "fake-irc");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void send(String line) {
        out.print(line + "\r\n");
        out.flush();
    }

    void botNotice(String text) {
        send(":Bot!bot@bot.host NOTICE " + clientNick + " :" + text);
    }

    private void serve() {
        try (Socket s = server.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
            String line;
            while ((line = in.readLine()) != null) {
                received.add(line);
                IrcMessage m = IrcMessage.parse(line);
                switch (m.command()) {
                    case "NICK" -> {
                        clientNick = m.param(0);
                        if (clientNick.endsWith("0")) { // exercise "nick in use" once in a while
                            send(":srv 433 * " + clientNick + " :Nickname is already in use");
                        } else {
                            send(":srv 001 " + clientNick + " :Welcome");
                        }
                    }
                    case "JOIN" -> {
                        String ch = m.param(0);
                        send(":" + clientNick + "!u@h JOIN :" + ch);
                        send(":srv 353 " + clientNick + " = " + ch + " :" + (botInChannel ? "@Bot " : "") + clientNick);
                        send(":srv 366 " + clientNick + " " + ch + " :End of /NAMES list.");
                    }
                    case "PRIVMSG" -> {
                        String text = m.text();
                        if (m.param(0).equalsIgnoreCase("Bot") && text.toLowerCase().startsWith("xdcc send #")) {
                            onRequest.request(this, clientNick, Integer.parseInt(text.substring("xdcc send #".length())));
                        } else if (m.param(0).equalsIgnoreCase("Bot") && text.startsWith("\u0001DCC RESUME") && acceptResumeAt >= 0) {
                            String[] p = text.replace("\u0001", "").split(" ");
                            send(":Bot!bot@bot.host PRIVMSG " + clientNick + " :\u0001DCC ACCEPT " + p[2] + " " + p[3] + " "
                                    + acceptResumeAt + "\u0001");
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

    /** Default behaviour: offer the file over DCC and serve it from the requested offset. */
    static void sendFile(FakeIrcNetwork net, String nick, int pack) throws IOException {
        net.botNotice("** Sending you pack #" + pack + " (\"" + net.fileName + "\")");
        net.offer();
    }

    void offer() throws IOException {
        dcc = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        long ip = 0x7F000001L;
        send(":Bot!bot@bot.host PRIVMSG " + clientNick + " :\u0001DCC SEND " + fileName + " " + ip + " "
                + dcc.getLocalPort() + " " + file.length + "\u0001");
        Thread t = new Thread(() -> {
            try (ServerSocket ss = dcc; Socket c = ss.accept()) {
                long from = Math.max(acceptResumeAt, 0);
                OutputStream o = c.getOutputStream();
                DataInputStream acks = new DataInputStream(c.getInputStream());
                int chunk = 32 * 1024;
                for (long off = from; off < file.length; off += chunk) {
                    int n = (int) Math.min(chunk, file.length - off);
                    o.write(file, (int) off, n);
                    o.flush();
                }
                // wait for the final acknowledgement, as real bots do
                long acked = 0;
                while (acked != (file.length & 0xFFFFFFFFL)) {
                    acked = acks.readInt() & 0xFFFFFFFFL;
                }
            } catch (IOException ignored) {
                // client went away
            }
        }, "fake-dcc");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException {
        server.close();
        if (dcc != null) {
            dcc.close();
        }
    }
}
