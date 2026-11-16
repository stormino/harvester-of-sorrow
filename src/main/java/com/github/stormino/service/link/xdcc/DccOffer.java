package com.github.stormino.service.link.xdcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A CTCP {@code DCC SEND <file> <ip> <port> [size] [token]} offer, or the
 * {@code DCC ACCEPT <file> <port> <position>} answer to a resume request.
 *
 * @param fileName offered file name
 * @param host     sender address (IPv4 dotted / IPv6), {@code null} for ACCEPT
 * @param port     port to connect to; 0 means passive (reverse) DCC
 * @param size     file size, or -1 when not given; for ACCEPT, the resume position
 * @param token    passive DCC token, or {@code null}
 */
public record DccOffer(String fileName, String host, int port, long size, String token) {

    public boolean passive() {
        return port == 0;
    }

    /** Parses a CTCP payload ("DCC SEND ..."); empty when it is not a DCC SEND. */
    public static Optional<DccOffer> parseSend(String ctcp) {
        List<String> t = tokens(ctcp);
        if (t.size() < 5 || !t.get(0).equalsIgnoreCase("DCC") || !t.get(1).equalsIgnoreCase("SEND")) {
            return Optional.empty();
        }
        try {
            String host = ip(t.get(3));
            int port = Integer.parseInt(t.get(4));
            long size = t.size() > 5 ? Long.parseLong(t.get(5)) : -1;
            String token = t.size() > 6 ? t.get(6) : null;
            return Optional.of(new DccOffer(t.get(2), host, port, size, token));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Parses "DCC ACCEPT <file> <port> <position>"; size() is the accepted position. */
    public static Optional<DccOffer> parseAccept(String ctcp) {
        List<String> t = tokens(ctcp);
        if (t.size() < 5 || !t.get(0).equalsIgnoreCase("DCC") || !t.get(1).equalsIgnoreCase("ACCEPT")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DccOffer(t.get(2), null, Integer.parseInt(t.get(3)), Long.parseLong(t.get(4)), null));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** DCC addresses are usually an unsigned 32-bit number; IPv6 and dotted forms are passed through. */
    static String ip(String raw) {
        if (raw.matches("\\d+")) {
            long n = Long.parseLong(raw);
            return ((n >> 24) & 255) + "." + ((n >> 16) & 255) + "." + ((n >> 8) & 255) + "." + (n & 255);
        }
        return raw;
    }

    /** Space-separated tokens; a token may be "quoted with spaces" (file names). */
    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            if (i >= s.length()) {
                break;
            }
            if (s.charAt(i) == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) {
                    end = s.length();
                }
                out.add(s.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = s.indexOf(' ', i);
                if (end < 0) {
                    end = s.length();
                }
                out.add(s.substring(i, end));
                i = end;
            }
        }
        return out;
    }
}
