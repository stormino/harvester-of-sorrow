package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Save the registered IRC nick")
public record IrcIdentityRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "MyNick") String nick,
        @Schema(description = "Empty or omitted: keep the current password") String password,
        String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean autoRegister
) {}
