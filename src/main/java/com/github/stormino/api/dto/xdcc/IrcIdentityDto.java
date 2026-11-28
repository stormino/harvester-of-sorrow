package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The registered IRC nick used where an XDCC channel needs one. The password is never returned.")
public record IrcIdentityDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Empty when none is set") String nick,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean passwordSet,
        @Schema(description = "Used only to register the nick") String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Register the nick where it is not registered yet") boolean autoRegister,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "SETTINGS (saved here), ENVIRONMENT (XDCC_IRC_* variables) or NONE",
                allowableValues = {"SETTINGS", "ENVIRONMENT", "NONE"}) String origin
) {}
