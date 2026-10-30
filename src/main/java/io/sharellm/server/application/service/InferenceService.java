package io.sharellm.server.application.service;

import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.application.port.outbound.InferenceQueue;
import io.sharellm.server.application.port.outbound.MonotonicClock;
import io.sharellm.server.application.settings.QueueSettings;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.exception.PayloadTooLargeException;
import io.sharellm.server.domain.exception.QueueFullException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Admits requests to the queue and waits for results, polling the database so any instance can deliver. */
public class InferenceService implements InferenceUseCase {
    static final int MAX_MODEL_NAME_LENGTH = 200;
    static final long MIN_RESULT_POLL_MILLIS = 50;

    private final InferenceQueue queue;
    private final QueueSettings settings;
    private final MonotonicClock clock;

    public InferenceService(InferenceQueue queue, QueueSettings settings, MonotonicClock clock) {
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public UUID enqueue(InferenceRequest request, String ownerHash) {
        String model = request.model();
        if (model == null || model.isBlank() || model.length() > MAX_MODEL_NAME_LENGTH)
            throw new InvalidRequestException("model is required");
        if (request.payloadJson().getBytes(StandardCharsets.UTF_8).length > settings.maxBodyBytes())
            throw new PayloadTooLargeException("Request body too large");
        // Admission control. Install a rate limiter in front for extremely high-volume deployments.
        if (settings.queueLimit() > 0 && queue.countPending() >= settings.queueLimit())
            throw new QueueFullException("ShareLLM queue full");
        return queue.submit(request, ownerHash);
    }

    @Override
    public InferenceOutcome submitAndAwait(InferenceRequest request, String ownerHash) throws InterruptedException {
        UUID id = enqueue(request, ownerHash);
        long deadline = clock.nanoTime() + Duration.ofSeconds(settings.waitSeconds()).toNanos();
        Duration pollInterval = Duration.ofMillis(Math.max(MIN_RESULT_POLL_MILLIS, settings.resultPollMillis()));
        while (clock.nanoTime() < deadline) {
            JobResult result = findResult(id, ownerHash);
            if (result.isFinished()) return new InferenceOutcome(id, result);
            clock.sleep(pollInterval);
        }
        return new InferenceOutcome(id, findResult(id, ownerHash));
    }

    @Override
    public JobResult findResult(UUID id, String ownerHash) {
        return queue.find(id, ownerHash).orElseThrow(() -> new ResourceNotFoundException("Unknown request"));
    }

    @Override
    public List<String> availableModels() { return queue.availableModels(); }
}
