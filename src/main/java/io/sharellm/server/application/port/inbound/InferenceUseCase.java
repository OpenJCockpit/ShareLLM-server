package io.sharellm.server.application.port.inbound;

import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import java.util.List;
import java.util.UUID;

/** Owner-facing operations: submit inference work and retrieve its result. */
public interface InferenceUseCase {
    UUID enqueue(InferenceRequest request, String ownerHash);

    /** Enqueues the request and waits up to the configured time for its result. */
    InferenceOutcome submitAndAwait(InferenceRequest request, String ownerHash) throws InterruptedException;

    /** @throws io.sharellm.server.domain.exception.ResourceNotFoundException for unknown IDs or another owner's request */
    JobResult findResult(UUID id, String ownerHash);

    List<String> availableModels();
}
