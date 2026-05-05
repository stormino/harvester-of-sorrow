package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level response from the RaiPlay search API.
 * Shape is best-guess pending real fixture capture; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlaySearchResponse(
        List<RaiPlaySearchResult> results,
        int total
) {}
