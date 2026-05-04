package com.github.stormino.model.source;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.stormino.model.MediaSource;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = VixSrcMetadata.class, name = "VIXSRC"),
        @JsonSubTypes.Type(value = RaiPlayMetadata.class, name = "RAIPLAY")
})
public sealed interface SourceMetadata permits VixSrcMetadata, RaiPlayMetadata {
    MediaSource source();
}
