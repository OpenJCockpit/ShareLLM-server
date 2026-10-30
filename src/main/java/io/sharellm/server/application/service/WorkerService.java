package io.sharellm.server.application.service;

import io.sharellm.server.application.port.inbound.WorkerUseCase;
import io.sharellm.server.application.port.outbound.MonotonicClock;
import io.sharellm.server.application.port.outbound.WorkerLeases;
import io.sharellm.server.application.settings.QueueSettings;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.WorkerRegistration;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates worker input and implements the long-poll claim on top of the lease port. */
public class WorkerService implements WorkerUseCase {
    static final int HEARTBEAT_INTERVAL_SECONDS = 10;
    static final int MAX_MODELS = 200;
    static final int MAX_MODEL_NAME_LENGTH = 200;
    static final Duration CLAIM_RETRY_DELAY = Duration.ofMillis(400);
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private final WorkerLeases leases;
    private final QueueSettings settings;
    private final MonotonicClock clock;

    public WorkerService(WorkerLeases leases, QueueSettings settings, MonotonicClock clock) {
        this.leases = leases;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public WorkerRegistration register(String clientId, List<String> models) {
        requireValidClientId(clientId);
        if (models == null || models.size() > MAX_MODELS || models.stream().anyMatch(WorkerService::isInvalidModelName))
            throw new InvalidRequestException("models must be a list of at most 200 model names");
        leases.register(clientId, List.copyOf(new LinkedHashSet<>(models)));
        return new WorkerRegistration(clientId, HEARTBEAT_INTERVAL_SECONDS, settings.leaseSeconds());
    }

    @Override
    public void heartbeat(String clientId) {
        requireValidClientId(clientId);
        leases.heartbeat(clientId);
    }

    @Override
    public Optional<Job> claim(String clientId, int waitSeconds) throws InterruptedException {
        requireValidClientId(clientId);
        int window = Math.min(Math.max(waitSeconds, 0), settings.pollSeconds());
        long deadline = clock.nanoTime() + Duration.ofSeconds(window).toNanos();
        while (true) {
            Optional<Job> job = leases.claim(clientId);
            if (job.isPresent() || clock.nanoTime() >= deadline) return job;
            clock.sleep(CLAIM_RETRY_DELAY);
        }
    }

    @Override
    public void complete(UUID jobId, String clientId, UUID leaseToken, String responseJson) {
        requireValidClientId(clientId);
        leases.complete(jobId, clientId, leaseToken, responseJson);
    }

    @Override
    public void fail(UUID jobId, String clientId, UUID leaseToken, String error) {
        requireValidClientId(clientId);
        leases.fail(jobId, clientId, leaseToken, error);
    }

    private static boolean isInvalidModelName(String model) {
        return model == null || model.isBlank() || model.length() > MAX_MODEL_NAME_LENGTH;
    }

    private static void requireValidClientId(String clientId) {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches())
            throw new InvalidRequestException("invalid clientId");
    }
}
