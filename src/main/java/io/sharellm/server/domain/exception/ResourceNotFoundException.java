package io.sharellm.server.domain.exception;

/** The addressed request or worker does not exist for this caller. */
public final class ResourceNotFoundException extends ShareLlmException {
    public ResourceNotFoundException(String message) { super(message); }
}
