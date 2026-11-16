package com.github.stormino.service.link.xdcc;

import java.util.ArrayList;
import java.util.List;

/**
 * One IRC protocol line: {@code [:prefix] COMMAND param1 param2 ... [:trailing]}.
 *
 * @param prefix  sender ({@code nick!user@host} or a server name), or {@code null}
 * @param command command or 3-digit numeric, upper case
 * @param params  parameters; the trailing one (after " :") is the last element
 */
public record IrcMessage(String prefix, String command, List<String> params) {

    public IrcMessage {
        params = List.copyOf(params);
    }

    public static IrcMessage parse(String line) {
        String rest = line;
        String prefix = null;
        if (rest.startsWith("@")) { // IRCv3 tags: not used
            int sp = rest.indexOf(' ');
            rest = sp < 0 ? "" : rest.substring(sp + 1);
        }
        if (rest.startsWith(":")) {
            int sp = rest.indexOf(' ');
            prefix = sp < 0 ? rest.substring(1) : rest.substring(1, sp);
            rest = sp < 0 ? "" : rest.substring(sp + 1);
        }
        List<String> params = new ArrayList<>();
        String trailing = null;
        int t = rest.indexOf(" :");
        if (rest.startsWith(":")) {
            trailing = rest.substring(1);
            rest = "";
        } else if (t >= 0) {
            trailing = rest.substring(t + 2);
            rest = rest.substring(0, t);
        }
        String[] parts = rest.trim().split(" +");
        String command = parts.length > 0 ? parts[0].toUpperCase() : "";
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                params.add(parts[i]);
            }
        }
        if (trailing != null) {
            params.add(trailing);
        }
        return new IrcMessage(prefix, command, params);
    }

    /** Nick part of the prefix ({@code nick!user@host} gives {@code nick}). */
    public String nick() {
        if (prefix == null) {
            return null;
        }
        int bang = prefix.indexOf('!');
        return bang < 0 ? prefix : prefix.substring(0, bang);
    }

    public String param(int i) {
        return i < params.size() ? params.get(i) : null;
    }

    /** Last parameter (the text of PRIVMSG/NOTICE). */
    public String text() {
        return params.isEmpty() ? null : params.get(params.size() - 1);
    }

    /** CTCP payload when the text is {@code \u0001...\u0001}, else {@code null}. */
    public String ctcp() {
        String text = text();
        if (text == null || text.length() < 2 || text.charAt(0) != '\u0001') {
            return null;
        }
        int end = text.indexOf('\u0001', 1);
        return end < 0 ? text.substring(1) : text.substring(1, end);
    }

    /** Text without mIRC colour/format codes. */
    public static String stripFormatting(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("\u0003(\\d{1,2}(,\\d{1,2})?)?", "").replaceAll("[\u0002\u000F\u0016\u001D\u001F\u0011\u001E]", "");
    }
}
