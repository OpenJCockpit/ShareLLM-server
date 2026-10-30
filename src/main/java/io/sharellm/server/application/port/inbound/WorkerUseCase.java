package io.sharellm.server.application.port.inbound;

import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.WorkerRegistration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Worker-facing operations: registration, liveness and the lease protocol. */
public interface WorkerUseCase {
    WorkerRegistration register(String clientId, List<String> models);

    void heartbeat(String clientId);

    /** Long-polls for a job for at most {@code waitSeconds}, capped by the configured poll window. */
    Optional<Job> claim(String clientId, int waitSeconds) throws InterruptedException;

    void complete(UUID jobId, String clientId, UUID leaseToken, String responseJson);

    void fail(UUID jobId, String clientId, UUID leaseToken, String error);
}
