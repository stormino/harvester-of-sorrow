package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Lightweight response from {@code /programmi/info/{uuid}.json}. Used only to
 * classify a search hit as movie vs TV without paying the cost of fetching the
 * full program page.
 *
 * <p>The {@code details} array contains a {@code seasons} entry only for TV
 * shows (e.g. {@code value="6 stagioni"}). Movies carry a {@code genre} entry
 * instead. Presence of the {@code seasons} key is the discriminator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlayProgramInfo(
        String name,
        String id,
        String description,
        @JsonProperty("path_id") String pathId,
        List<ProgramDetail> details
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramDetail(String type, String key, String value) {}

    /** {@code true} when the program info advertises a "seasons" detail. */
    public boolean isTvShow() {
        return details != null
                && details.stream().anyMatch(d -> "seasons".equals(d.key()));
    }

    /**
     * Number of seasons parsed from a string like {@code "6 stagioni"}.
     * Returns empty if the program is not a TV show or the value can't be parsed.
     */
    public Optional<Integer> seasonCount() {
        if (details == null) return Optional.empty();
        return details.stream()
                .filter(d -> "seasons".equals(d.key()) && d.value() != null)
                .map(d -> {
                    String[] parts = d.value().trim().split("\\s+", 2);
                    try {
                        return Integer.parseInt(parts[0]);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /** Year extracted from a {@code {"key":"year","value":"2016"}} detail entry. */
    public Optional<Integer> extractYear() {
        if (details == null) return Optional.empty();
        return details.stream()
                .filter(d -> "year".equals(d.key()) && d.value() != null)
                .map(d -> {
                    try { return Integer.parseInt(d.value().trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
