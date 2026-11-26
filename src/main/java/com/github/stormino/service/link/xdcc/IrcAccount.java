package com.github.stormino.service.link.xdcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A registered nick on an IRC network, for channels that only let registered users in
 * ("You need a registered nick to join that channel").
 *
 * @param server        network host, or a domain matching its servers ("scenep2p.net" matches irc.scenep2p.net)
 * @param nick          registered nick (also the services account name)
 * @param password      services password
 * @param registerEmail when set, the nick is registered with NickServ using this e-mail if it is not yet
 */
public record IrcAccount(String server, String nick, String password, String registerEmail) {

    public IrcAccount {
        server = server.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "IrcAccount[" + server + ", " + nick + "]"; // keep the password out of logs
    }

    public boolean matches(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals(server) || h.endsWith("." + server);
    }

    /**
     * Parses {@code "server=nick:password, other.net=nick2:password2"} (IRC passwords cannot contain
     * spaces; commas separate entries).
     */
    public static List<IrcAccount> parse(String spec, String registerEmail) {
        List<IrcAccount> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        String email = registerEmail == null || registerEmail.isBlank() ? null : registerEmail.strip();
        for (String entry : spec.split("[,\\s]+")) {
            if (entry.isBlank()) {
                continue;
            }
            int eq = entry.indexOf('=');
            int colon = entry.indexOf(':', eq + 1);
            if (eq <= 0 || colon <= eq + 1 || colon == entry.length() - 1) {
                throw new IllegalArgumentException("IRC account must look like server=nick:password, got '"
                        + entry.substring(0, Math.max(eq, 0)) + "=…'");
            }
            out.add(new IrcAccount(entry.substring(0, eq), entry.substring(eq + 1, colon),
                    entry.substring(colon + 1), email));
        }
        return out;
    }

    /** The most specific account for {@code host} (an exact host wins over a domain). */
    public static Optional<IrcAccount> forHost(List<IrcAccount> accounts, String host) {
        return accounts.stream()
                .filter(a -> a.matches(host))
                .max((a, b) -> Integer.compare(a.server().length(), b.server().length()));
    }
}
