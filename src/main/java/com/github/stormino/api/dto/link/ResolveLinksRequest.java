package com.github.stormino.api.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Several links of one provider to queue together (e.g. XDCC packs picked from a search)")
public record ResolveLinksRequest(
        @NotEmpty @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> urls,
        @Schema(description = "Name shown for the group, e.g. the search words") String name
) {}
