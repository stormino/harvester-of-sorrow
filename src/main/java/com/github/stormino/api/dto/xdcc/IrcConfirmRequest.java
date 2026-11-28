package com.github.stormino.api.dto.xdcc;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The code NickServ e-mailed after registering the nick")
public record IrcConfirmRequest(@NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {}
