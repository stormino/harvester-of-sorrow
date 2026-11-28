package com.github.stormino.api;

import com.github.stormino.api.dto.xdcc.IrcIdentityDto;
import com.github.stormino.api.dto.xdcc.IrcNetworkDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IRC account API v1")
class IrcAccountApiIT extends AbstractApiIT {

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM irc_identity");
        jdbc.execute("DELETE FROM irc_network");
    }

    @Test
    @DisplayName("PUT /xdcc/identity saves the nick; the password is never returned and kept when left empty")
    void saveAndRead() {
        IrcIdentityDto none = rest.getForObject("/api/v1/xdcc/identity", IrcIdentityDto.class);
        assertThat(none.origin()).isEqualTo("NONE");
        assertThat(none.passwordSet()).isFalse();

        ResponseEntity<String> saved = rest.exchange("/api/v1/xdcc/identity", HttpMethod.PUT,
                json("{\"nick\":\"MyNick\",\"password\":\"secret1\",\"email\":\"me@example.org\",\"autoRegister\":true}"), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).doesNotContain("secret1");

        rest.exchange("/api/v1/xdcc/identity", HttpMethod.PUT,
                json("{\"nick\":\"Other\",\"password\":\"\",\"email\":\"\",\"autoRegister\":false}"), String.class);
        IrcIdentityDto id = rest.getForObject("/api/v1/xdcc/identity", IrcIdentityDto.class);
        assertThat(id.nick()).isEqualTo("Other");
        assertThat(id.passwordSet()).isTrue();
        assertThat(id.origin()).isEqualTo("SETTINGS");
        assertThat(jdbc.queryForObject("SELECT password FROM irc_identity", String.class)).isEqualTo("secret1");

        rest.delete("/api/v1/xdcc/identity");
        assertThat(rest.getForObject("/api/v1/xdcc/identity", IrcIdentityDto.class).origin()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("PUT /xdcc/identity refuses unusable values")
    void validation() {
        ResponseEntity<String> r = rest.exchange("/api/v1/xdcc/identity", HttpMethod.PUT,
                json("{\"nick\":\"MyNick\",\"password\":\"pw\",\"autoRegister\":false}"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("Password");
        r = rest.exchange("/api/v1/xdcc/identity", HttpMethod.PUT,
                json("{\"nick\":\"MyNick\",\"password\":\"secret1\",\"autoRegister\":true}"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("E-mail");
    }

    @Test
    @DisplayName("GET /xdcc/networks lists remembered networks; DELETE forgets one")
    void networks() {
        jdbc.update("INSERT INTO irc_network (host, port, nick, state, message, updated_at) VALUES (?,?,?,?,?,?)",
                "irc.example.net", 6667, "MyNick", "NEEDS_CONFIRM", "check the e-mail", "2026-11-28T10:00:00");
        IrcNetworkDto[] list = rest.getForObject("/api/v1/xdcc/networks", IrcNetworkDto[].class);
        assertThat(list).hasSize(1);
        assertThat(list[0].host()).isEqualTo("irc.example.net");
        assertThat(list[0].state()).isEqualTo("NEEDS_CONFIRM");
        assertThat(list[0].connected()).isFalse();
        assertThat(list[0].needsAccount()).isTrue();

        rest.delete("/api/v1/xdcc/networks/irc.example.net");
        assertThat(rest.getForObject("/api/v1/xdcc/networks", IrcNetworkDto[].class)).isEmpty();
    }
}
