package io.sharellm.server.domain.exception;

/** The queue reached its configured admission limit. */
public final class QueueFullException extends ShareLlmException {
    public QueueFullException(String message) { super(message); }
}
