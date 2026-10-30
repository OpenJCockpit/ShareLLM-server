package io.sharellm.server.domain.model;

import java.util.UUID;

/** A queued inference request leased to one worker under a fencing token. */
public record Job(UUID id, String endpoint, String model, String payload, UUID leaseToken, int attempts) {}
