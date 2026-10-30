package io.sharellm.server.domain.exception;

/** A worker wrote with a lease token that is no longer current. */
public final class LeaseConflictException extends ShareLlmException {
    public LeaseConflictException(String message) { super(message); }
}
