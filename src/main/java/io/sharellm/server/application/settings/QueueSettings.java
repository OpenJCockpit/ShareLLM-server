package io.sharellm.server.application.settings;

/** Timing and capacity limits of the durable queue. */
public record QueueSettings(
        int heartbeatTimeoutSeconds,
        int leaseSeconds,
        int maxAttempts,
        int waitSeconds,
        int pollSeconds,
        int resultPollMillis,
        int retentionHours,
        int queueLimit,
        int maxBodyBytes) {}
