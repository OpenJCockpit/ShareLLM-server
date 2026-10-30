package io.sharellm.server.domain.exception;

/** Base type of every rule violation the application reports to callers. */
public abstract sealed class ShareLlmException extends RuntimeException
        permits AuthenticationException, InvalidRequestException, LeaseConflictException,
                PayloadTooLargeException, QueueFullException, ResourceNotFoundException {
    protected ShareLlmException(String message) { super(message); }
}
