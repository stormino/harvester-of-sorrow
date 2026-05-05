package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level envelope of the RaiPlay search response. The interesting bits live
 * under {@code agg}: {@code video} (specific videos — films + episodes) and
 * {@code titoli} (programs / series roots).
 *
 * <p>For now we only consume {@code agg.video.cards[]} since each card maps
 * 1:1 to a downloadable item. Series roots in {@code titoli} would require
 * an additional descriptor lookup to enumerate seasons.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlaySearchResponse(Agg agg) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Agg(
            Integer totale,
            VideoBucket video,
            TitoliBucket titoli
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VideoBucket(
            Integer totale,
            List<RaiPlaySearchResult> cards
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TitoliBucket(
            Integer totale,
            List<ProgramCard> cards
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramCard(
            String id,
            String titolo,
            String tipo,
            @JsonProperty("path_id") String pathId,
            @JsonProperty("info_url") String infoUrl
    ) {}
}
