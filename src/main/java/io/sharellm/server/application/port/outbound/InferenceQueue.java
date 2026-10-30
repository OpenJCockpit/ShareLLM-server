package io.sharellm.server.application.port.outbound;

import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable storage of submitted requests and their results. */
public interface InferenceQueue {
    UUID submit(InferenceRequest request, String ownerHash);

    /** Jobs that are waiting for or held by a worker. */
    long countPending();

    Optional<JobResult> find(UUID id, String ownerHash);

    /** Distinct model names advertised by workers with a live heartbeat. */
    List<String> availableModels();
}
