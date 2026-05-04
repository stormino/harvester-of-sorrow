package com.github.stormino.model.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceMetadataJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void vixsrcMetadataRoundTrip() throws Exception {
        SourceMetadata original = new VixSrcMetadata(550, 1, 4);
        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"VIXSRC\""), json);

        SourceMetadata parsed = mapper.readValue(json, SourceMetadata.class);
        assertInstanceOf(VixSrcMetadata.class, parsed);
        VixSrcMetadata vix = (VixSrcMetadata) parsed;
        assertEquals(550, vix.tmdbId());
        assertEquals(1, vix.season());
        assertEquals(4, vix.episode());
    }

    @Test
    void raiplayMetadataRoundTrip() throws Exception {
        SourceMetadata original = new RaiPlayMetadata(
                "/video/2018/12/COSMONAUTA-f5cbe4fd-7eb2-490f-af5b-ff0cd2009973.html",
                "f5cbe4fd-7eb2-490f-af5b-ff0cd2009973",
                null, null, null);
        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"RAIPLAY\""), json);

        SourceMetadata parsed = mapper.readValue(json, SourceMetadata.class);
        assertInstanceOf(RaiPlayMetadata.class, parsed);
        RaiPlayMetadata rai = (RaiPlayMetadata) parsed;
        assertEquals("f5cbe4fd-7eb2-490f-af5b-ff0cd2009973", rai.contentUuid());
    }

    @Test
    void backfillJsonFromV2MigrationDeserializes() throws Exception {
        // Mirrors what the V2 SQL UPDATE produces via SQLite json_object():
        String backfilled = "{\"type\":\"VIXSRC\",\"tmdbId\":550,\"season\":null,\"episode\":null}";

        SourceMetadata parsed = mapper.readValue(backfilled, SourceMetadata.class);
        assertInstanceOf(VixSrcMetadata.class, parsed);
        assertEquals(550, ((VixSrcMetadata) parsed).tmdbId());
    }
}
