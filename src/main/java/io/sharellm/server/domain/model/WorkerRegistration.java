package io.sharellm.server.domain.model;

/** The contract a worker receives when it registers. */
public record WorkerRegistration(String clientId, int heartbeatEverySeconds, int leaseSeconds) {}
