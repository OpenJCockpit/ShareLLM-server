package io.sharellm.server.domain.exception;

/** The caller presented a missing or invalid credential. */
public final class AuthenticationException extends ShareLlmException {
    public AuthenticationException(String message) { super(message); }
}
