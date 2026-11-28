package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An IRC network: connected now, or remembered because a channel there needed the registered nick")
public record IrcNetworkDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "irc.scenep2p.net") String host,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int port,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean connected,
        String nick,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ANONYMOUS", "LOGGING_IN", "LOGGED_IN", "NEEDS_CONFIRM", "FAILED", "NO_ACCOUNT"}) String state,
        String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A channel here needs the registered nick") boolean needsAccount
) {}
