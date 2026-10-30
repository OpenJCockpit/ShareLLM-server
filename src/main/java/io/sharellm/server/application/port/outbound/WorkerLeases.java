package io.sharellm.server.application.port.outbound;

import io.sharellm.server.domain.model.Job;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Worker liveness and the lease protocol. Writes with a stale lease token must be rejected. */
public interface WorkerLeases {
    void register(String clientId, List<String> models);

    /** @throws io.sharellm.server.domain.exception.ResourceNotFoundException if the worker never registered */
    void heartbeat(String clientId);

    /** Atomically leases the oldest ready job for one of the worker's models, if the worker holds no lease. */
    Optional<Job> claim(String clientId);

    /** @throws io.sharellm.server.domain.exception.LeaseConflictException if the lease is no longer current */
    void complete(UUID jobId, String clientId, UUID leaseToken, String responseJson);

    /** @throws io.sharellm.server.domain.exception.LeaseConflictException if the lease is no longer current */
    void fail(UUID jobId, String clientId, UUID leaseToken, String error);
}
