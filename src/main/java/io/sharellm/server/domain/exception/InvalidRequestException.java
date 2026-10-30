package io.sharellm.server.domain.exception;

/** The request is malformed or violates an input rule. */
public final class InvalidRequestException extends ShareLlmException {
    public InvalidRequestException(String message) { super(message); }
}
