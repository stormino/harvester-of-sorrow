package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.dto.xdcc.IrcConfirmRequest;
import com.github.stormino.api.dto.xdcc.IrcIdentityDto;
import com.github.stormino.api.dto.xdcc.IrcIdentityRequest;
import com.github.stormino.api.dto.xdcc.IrcNetworkDto;
import com.github.stormino.service.link.xdcc.IrcConnections;
import com.github.stormino.service.link.xdcc.IrcIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Tag(name = "XDCC", description = "Search XDCC packs on IRC (PW Search Engine, xdcc.eu)")
@RestController
@RequestMapping("/api/v1/xdcc")
public class IrcAccountController {

    private final IrcIdentityService identity;
    private final ObjectProvider<IrcConnections> connections;

    public IrcAccountController(IrcIdentityService identity, ObjectProvider<IrcConnections> connections) {
        this.identity = identity;
        this.connections = connections;
    }

    @Operation(summary = "The registered IRC nick",
            description = "Used only on networks where a channel needs a registered nick. Saved in Settings, "
                    + "else taken from the XDCC_IRC_* variables.")
    @GetMapping("/identity")
    public IrcIdentityDto identity() {
        return identity.identity()
                .map(i -> new IrcIdentityDto(i.nick(), true, i.email(), i.autoRegister(), i.origin().name()))
                .orElse(new IrcIdentityDto("", false, null, false, IrcIdentityService.Origin.NONE.name()));
    }

    @Operation(summary = "Save the registered IRC nick", description = "Used from the next login on; leave the password empty to keep it.")
    @PutMapping("/identity")
    public IrcIdentityDto saveIdentity(@RequestBody IrcIdentityRequest req) {
        try {
            identity.save(req.nick(), req.password(), req.email(), req.autoRegister());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return identity();
    }

    @Operation(summary = "Remove the registered IRC nick saved in Settings")
    @DeleteMapping("/identity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearIdentity() {
        identity.clear();
    }

    @Operation(summary = "IRC networks: connected now, or where a channel needed the registered nick")
    @GetMapping("/networks")
    public List<IrcNetworkDto> networks() {
        IrcConnections c = connections.getIfAvailable();
        if (c == null) {
            return identity.networks().stream()
                    .map(n -> new IrcNetworkDto(n.host(), n.port(), false, n.nick(), n.state().name(), n.message(), true))
                    .toList();
        }
        return c.networks().stream().map(IrcAccountController::toDto).toList();
    }

    @Operation(summary = "Confirm the nick with the code NickServ e-mailed",
            description = "Connects to the network if needed and sends NickServ CONFIRM <code>.")
    @PostMapping("/networks/{host}/confirm")
    public IrcNetworkDto confirm(@PathVariable String host, @Valid @RequestBody IrcConfirmRequest req) {
        IrcConnections c = connections.getIfAvailable();
        if (c == null) {
            throw ApiException.badRequest("XDCC is disabled");
        }
        try {
            return toDto(c.confirm(host, req.code()));
        } catch (IOException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @Operation(summary = "Forget that a network needs the registered nick")
    @DeleteMapping("/networks/{host}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String host) {
        identity.forgetNetwork(host);
    }

    private static IrcNetworkDto toDto(IrcConnections.NetworkStatus n) {
        return new IrcNetworkDto(n.host(), n.port(), n.connected(), n.nick(), n.state().name(), n.message(), n.needsAccount());
    }
}
