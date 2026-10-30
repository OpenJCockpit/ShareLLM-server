package io.sharellm.server.adapter.inbound.web.worker;

import io.sharellm.server.domain.model.Job;
import java.util.UUID;

/** A leased job as sent to the worker; {@code payload} is the Ollama request body as a JSON string. */
public record ClaimedJobResponse(UUID id, String endpoint, String model, String payload, UUID leaseToken, int attempts) {
    static ClaimedJobResponse from(Job job) {
        return new ClaimedJobResponse(job.id(), job.endpoint(), job.model(), job.payload(), job.leaseToken(), job.attempts());
    }
}
