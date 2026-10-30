package io.sharellm.server.domain.model;

import java.util.UUID;

/** The state of a submitted request once the synchronous wait ended. */
public record InferenceOutcome(UUID requestId, JobResult result) {}
