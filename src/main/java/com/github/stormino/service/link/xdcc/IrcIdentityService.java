package com.github.stormino.service.link.xdcc;

import com.github.stormino.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The registered IRC nick (Settings &gt; IRC account, else the {@code XDCC_IRC_*} variables) and the
 * networks found to need it, with how logging in there last went.
 */
@Slf4j
@Service
public class IrcIdentityService {

    public enum Origin { SETTINGS, ENVIRONMENT, NONE }

    /** The identity as configured; the password is only used inside HoS. */
    public record Identity(String nick, String password, String email, boolean autoRegister, Origin origin) {

        /** The account to log in with: the e-mail is only passed on when registering is allowed. */
        public IrcAccount account() {
            return new IrcAccount(nick, password, autoRegister && email != null && !email.isBlank() ? email : null);
        }

        @Override
        public String toString() {
            return "Identity[" + nick + ", " + origin + "]";
        }
    }

    public record Network(String host, int port, String nick, IrcClient.AuthState state, String message,
                          LocalDateTime updatedAt) {
    }

    private final JdbcClient jdbc;
    private final AppProperties.Link.Xdcc config;

    public IrcIdentityService(JdbcClient jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.config = properties.getLink().getXdcc();
    }

    public Optional<Identity> identity() {
        Optional<Identity> saved = jdbc.sql("SELECT nick, password, email, auto_register FROM irc_identity WHERE id = 1")
                .query((rs, n) -> new Identity(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4) == 1, Origin.SETTINGS))
                .optional();
        if (saved.isPresent()) {
            return saved;
        }
        if (!config.getIrcNick().isBlank() && !config.getIrcPassword().isBlank()) {
            return Optional.of(new Identity(config.getIrcNick().strip(), config.getIrcPassword().strip(),
                    blankToNull(config.getIrcEmail()), config.isIrcAutoRegister(), Origin.ENVIRONMENT));
        }
        return Optional.empty();
    }

    /** The account to log in with, or null when none is configured. */
    public IrcAccount account() {
        return identity().map(Identity::account).orElse(null);
    }

    /**
     * Saves the identity. A null or blank {@code password} keeps the current one.
     *
     * @throws IllegalArgumentException when a value cannot be used
     */
    public Identity save(String nick, String password, String email, boolean autoRegister) {
        String n = nick == null ? "" : nick.strip();
        String pw = password == null || password.isBlank()
                ? identity().map(Identity::password).orElse(null) : password.strip();
        String mail = blankToNull(email);
        String problem = IrcAccount.validate(n, pw, mail, autoRegister);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        jdbc.sql("""
                        INSERT INTO irc_identity (id, nick, password, email, auto_register, updated_at)
                        VALUES (1, :nick, :password, :email, :auto, :now)
                        ON CONFLICT (id) DO UPDATE SET nick = excluded.nick, password = excluded.password,
                            email = excluded.email, auto_register = excluded.auto_register, updated_at = excluded.updated_at""")
                .param("nick", n).param("password", pw).param("email", mail)
                .param("auto", autoRegister ? 1 : 0).param("now", LocalDateTime.now().toString())
                .update();
        log.info("IRC: account saved in Settings (nick {})", n);
        return identity().orElseThrow();
    }

    /** Forgets the identity saved in Settings (the environment defaults apply again, if any). */
    public void clear() {
        jdbc.sql("DELETE FROM irc_identity").update();
    }

    public boolean needsAccount(String host) {
        return jdbc.sql("SELECT COUNT(*) FROM irc_network WHERE host = :h")
                .param("h", host.toLowerCase(Locale.ROOT)).query(Integer.class).single() > 0;
    }

    public void networkState(String host, int port, String nick, IrcClient.AuthState state, String message) {
        jdbc.sql("""
                        INSERT INTO irc_network (host, port, nick, state, message, updated_at)
                        VALUES (:host, :port, :nick, :state, :message, :now)
                        ON CONFLICT (host) DO UPDATE SET port = excluded.port, nick = excluded.nick,
                            state = excluded.state, message = excluded.message, updated_at = excluded.updated_at""")
                .param("host", host.toLowerCase(Locale.ROOT)).param("port", port).param("nick", nick)
                .param("state", state.name()).param("message", message).param("now", LocalDateTime.now().toString())
                .update();
    }

    public List<Network> networks() {
        return jdbc.sql("SELECT host, port, nick, state, message, updated_at FROM irc_network ORDER BY host")
                .query((rs, n) -> new Network(rs.getString(1), rs.getInt(2), rs.getString(3),
                        parseState(rs.getString(4)), rs.getString(5),
                        rs.getString(6) == null ? null : LocalDateTime.parse(rs.getString(6))))
                .list();
    }

    public Optional<Network> network(String host) {
        return networks().stream().filter(n -> n.host().equalsIgnoreCase(host)).findFirst();
    }

    public void forgetNetwork(String host) {
        jdbc.sql("DELETE FROM irc_network WHERE host = :h").param("h", host.toLowerCase(Locale.ROOT)).update();
    }

    private static IrcClient.AuthState parseState(String s) {
        try {
            return IrcClient.AuthState.valueOf(s);
        } catch (RuntimeException e) {
            return IrcClient.AuthState.FAILED;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
