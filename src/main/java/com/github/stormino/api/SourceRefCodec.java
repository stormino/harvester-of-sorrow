package com.github.stormino.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.SourceMetadata;
import org.springframework.stereotype.Component;

/**
 * Encodes {@link SourceMetadata} as the opaque {@code sourceRef} string the API hands
 * to clients, and decodes it back. The string is the polymorphic JSON of the metadata.
 */
@Component
public class SourceRefCodec {

    private final ObjectMapper objectMapper;

    public SourceRefCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(SourceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise source metadata", e);
        }
    }

    /**
     * @throws IllegalArgumentException when the ref is malformed or belongs to another source
     */
    public SourceMetadata decode(String sourceRef, MediaSource expectedSource) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        SourceMetadata metadata;
        try {
            metadata = objectMapper.readValue(sourceRef, SourceMetadata.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed sourceRef");
        }
        if (expectedSource != null && metadata.source() != expectedSource) {
            throw new IllegalArgumentException("sourceRef belongs to " + metadata.source() + ", not " + expectedSource);
        }
        return metadata;
    }
}
