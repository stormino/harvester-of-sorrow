package com.github.stormino.service.link.xdcc;

import com.github.stormino.service.link.LinkCancelledException;
import com.github.stormino.service.link.LinkControl;
import com.github.stormino.service.link.LinkException;
import com.github.stormino.service.link.LinkProgress;
import lombok.extern.slf4j.Slf4j;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One XDCC request: asks the bot for the pack, follows its replies (queued, refused, ...), takes
 * the DCC offer and receives the file into {@code part}, resuming it when it already has data.
 */
@Slf4j
final class XdccTransfer {

    /** Bot reply that ends the request. */
    private static final List<String> REFUSALS = List.of(
            "invalid pack", "pack non valido", "pacchetto non valido", "doesn't exist", "does not exist",
            "non esiste", "denied", "negat", "not allowed", "non consentit", "closed", "chiuso",
            "you must be on", "devi essere", "must be in", "not on a known channel", "banned", "bannat",
            "too many", "troppi", "you already requested", "hai gia");
    /** Bot reply that means "wait". */
    private static final List<String> QUEUED = List.of("queue", "coda", "position", "posizione");

    private final IrcClient irc;
    private final XdccLink link;
    private final Duration replyTimeout;
    private final Duration queueTimeout;

    private sealed interface Event permits Offer, Accept, Notice {
    }

    private record Offer(DccOffer offer) implements Event {
    }

    private record Accept(DccOffer accept) implements Event {
    }

    private record Notice(String text) implements Event {
    }

    XdccTransfer(IrcClient irc, XdccLink link, Duration replyTimeout, Duration queueTimeout) {
        this.irc = irc;
        this.link = link;
        this.replyTimeout = replyTimeout;
        this.queueTimeout = queueTimeout;
    }

    /** Downloads the pack into {@code part} (appending when resuming); returns the bytes in the file. */
    long run(Path part, LinkProgress progress, LinkControl control) {
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        Consumer<IrcMessage> listener = m -> {
            if (!link.bot().equalsIgnoreCase(m.nick())) {
                return;
            }
            if (!m.command().equals("PRIVMSG") && !m.command().equals("NOTICE")) {
                return;
            }
            String ctcp = m.ctcp();
            if (ctcp != null) {
                DccOffer.parseSend(ctcp).ifPresent(o -> events.add(new Offer(o)));
                DccOffer.parseAccept(ctcp).ifPresent(a -> events.add(new Accept(a)));
            } else if (m.text() != null) {
                events.add(new Notice(IrcMessage.stripFormatting(m.text()).strip()));
            }
        };
        irc.addListener(listener);
        boolean queued = false;
        try {
            progress.status("Joining " + link.channel());
            irc.join(link.channel(), Duration.ofSeconds(30));
            if (!irc.isMember(link.channel(), link.bot())) {
                throw new LinkException("Bot " + link.bot() + " is not in " + link.channel() + " (offline?)");
            }
            irc.privmsg(link.bot(), "xdcc send #" + link.pack());
            progress.status("Asked " + link.bot() + " for pack #" + link.pack());
            long deadline = System.nanoTime() + replyTimeout.toNanos();
            while (true) {
                if (control.isCancelled()) {
                    throw new LinkCancelledException("Cancelled");
                }
                if (!irc.isConnected()) {
                    throw new LinkException("Disconnected from " + irc.server());
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new LinkException(queued
                            ? "Still queued by " + link.bot() + " after " + queueTimeout.toMinutes() + " minutes"
                            : "Bot " + link.bot() + " did not answer");
                }
                Event e = events.poll(Math.min(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                if (e instanceof Notice n) {
                    String low = n.text().toLowerCase(Locale.ROOT);
                    log.info("XDCC {}: {}", link.bot(), n.text());
                    if (REFUSALS.stream().anyMatch(low::contains)) {
                        throw new LinkException(link.bot() + ": " + n.text());
                    }
                    if (QUEUED.stream().anyMatch(low::contains)) {
                        queued = true;
                        deadline = System.nanoTime() + queueTimeout.toNanos();
                    }
                    progress.status(link.bot() + ": " + n.text());
                } else if (e instanceof Offer o) {
                    return receive(o.offer(), part, events, progress, control);
                }
            }
        } catch (IOException e) {
            throw new LinkException("XDCC: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkCancelledException("Interrupted");
        } finally {
            irc.removeListener(listener);
            if (control.isCancelled() && queued) {
                try {
                    irc.privmsg(link.bot(), "xdcc remove #" + link.pack());
                } catch (IOException ignored) {
                    // leaving the queue is best effort
                }
            }
        }
    }

    private long receive(DccOffer offer, Path part, BlockingQueue<Event> events, LinkProgress progress,
                         LinkControl control) throws IOException, InterruptedException {
        if (offer.passive()) {
            throw new LinkException(link.bot() + " offers passive DCC only, which needs an open inbound port (not supported)");
        }
        long total = offer.size() > 0 ? offer.size() : link.size() != null ? link.size() : -1;
        long have = Files.exists(part) ? Files.size(part) : 0;
        long start = 0;
        if (have > 0 && (total < 0 || have < total)) {
            irc.ctcp(link.bot(), "DCC RESUME " + quote(offer.fileName()) + " " + offer.port() + " " + have);
            long until = System.nanoTime() + replyTimeout.toNanos();
            while (System.nanoTime() < until) {
                Event e = events.poll(1, TimeUnit.SECONDS);
                if (e instanceof Accept a && a.accept().port() == offer.port()) {
                    start = a.accept().size();
                    break;
                }
                if (control.isCancelled()) {
                    throw new LinkCancelledException("Cancelled");
                }
            }
            if (start == 0) {
                log.info("XDCC {}: resume not accepted, starting over", link.bot());
            }
        } else if (have > 0 && have == total) {
            return have; // already complete
        }

        progress.status(null);
        try (Socket s = new Socket()) {
            control.onCancel(() -> {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
            });
            s.connect(new InetSocketAddress(offer.host(), offer.port()), 30_000);
            s.setSoTimeout(120_000);
            InputStream in = s.getInputStream();
            DataOutputStream ack = new DataOutputStream(s.getOutputStream());
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    start > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                if (start > 0 && start != have) {
                    throw new LinkException("Bot resumed at " + start + " but " + have + " bytes are on disk");
                }
                byte[] buf = new byte[64 * 1024];
                long done = start;
                long windowStart = System.nanoTime();
                long windowBytes = 0;
                int n;
                while ((total < 0 || done < total) && (n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    windowBytes += n;
                    ack.writeInt((int) done); // classic DCC acknowledgement: bytes received, low 32 bits
                    long now = System.nanoTime();
                    if (now - windowStart >= 1_000_000_000L) {
                        progress.update(done, total, windowBytes * 1e9 / (now - windowStart));
                        windowStart = now;
                        windowBytes = 0;
                    }
                }
                ack.flush();
                progress.update(done, total, 0);
                if (total > 0 && done < total) {
                    throw new LinkException("Transfer interrupted at " + done + " of " + total + " bytes");
                }
                return done;
            }
        } catch (SocketTimeoutException e) {
            throw new LinkException("Transfer stalled (no data for 2 minutes)", e);
        } catch (IOException e) {
            if (control.isCancelled()) {
                throw new LinkCancelledException("Cancelled");
            }
            throw e;
        }
    }

    private static String quote(String name) {
        return name.contains(" ") ? "\"" + name + "\"" : name;
    }
}
