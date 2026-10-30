package io.sharellm.server.domain.exception;

/** The request body exceeds the configured size limit. */
public final class PayloadTooLargeException extends ShareLlmException {
    public PayloadTooLargeException(String message) { super(message); }
}
