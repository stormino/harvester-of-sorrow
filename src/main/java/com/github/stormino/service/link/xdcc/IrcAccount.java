package com.github.stormino.service.link.xdcc;

import java.util.regex.Pattern;

/**
 * The registered IRC nick HoS uses on networks whose channels only let registered users in
 * ("You need a registered nick to join that channel").
 *
 * @param nick          registered nick (also the services account name)
 * @param password      services password
 * @param registerEmail when set, the nick is registered with NickServ using this e-mail where it is not yet
 */
public record IrcAccount(String nick, String password, String registerEmail) {

    /** RFC 2812 nick characters; most networks allow up to 30. */
    private static final Pattern NICK = Pattern.compile("[A-Za-z\\[\\]\\\\`_^{|}][A-Za-z0-9\\[\\]\\\\`_^{|}-]{1,29}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    @Override
    public String toString() {
        return "IrcAccount[" + nick + "]"; // keep the password out of logs
    }

    /** Why these values cannot be used, or null when they can. */
    public static String validate(String nick, String password, String email, boolean register) {
        if (nick == null || !NICK.matcher(nick).matches()) {
            return "Nick: 2-30 letters, digits or - _ [ ] { } | ^ `, not starting with a digit or -";
        }
        if (password == null || password.length() < 5 || password.chars().anyMatch(Character::isWhitespace)) {
            return "Password: at least 5 characters, no spaces";
        }
        if (password.equalsIgnoreCase(nick)) {
            return "Password: must not be the nick";
        }
        if (register && (email == null || !EMAIL.matcher(email).matches())) {
            return "E-mail: needed to register the nick";
        }
        return null;
    }
}
