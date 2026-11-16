package com.github.stormino.service.link;

/** A link could not be resolved or downloaded; the message is meant for the user. */
public class LinkException extends RuntimeException {
    public LinkException(String message) {
        super(message);
    }

    public LinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
