package io.sharellm.server.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.sharellm.server.application.port.outbound.InferenceQueue;
import io.sharellm.server.application.settings.QueueSettings;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.exception.PayloadTooLargeException;
import io.sharellm.server.domain.exception.QueueFullException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import io.sharellm.server.domain.model.JobState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InferenceServiceTest {
    private static final String OWNER = "owner-hash";
    private static final UUID ID = UUID.randomUUID();
    private static final JobResult PENDING = new JobResult(ID, "chat", "m", JobState.READY, null, null);
    private static final JobResult DONE = new JobResult(ID, "chat", "m", JobState.COMPLETED, "{}", 200);

    private final InferenceQueue queue = mock(InferenceQueue.class);
    private final FakeClock clock = new FakeClock();

    private InferenceService service(int waitSeconds, int pollMillis, int queueLimit, int maxBodyBytes) {
        return new InferenceService(queue, new QueueSettings(35, 45, 3, waitSeconds, 15, pollMillis, 24,
                queueLimit, maxBodyBytes), clock);
    }

    private InferenceService service() { return service(1, 350, 0, 1024); }

    private static InferenceRequest request(String model, String payload) {
        return new InferenceRequest(InferenceEndpoint.CHAT, model, payload);
    }

    @Test
    void enqueueSubmitsValidRequestsWithoutCountingWhenUnlimited() {
        InferenceRequest request = request("llama3", "{}");
        when(queue.submit(request, OWNER)).thenReturn(ID);
        assertThat(service().enqueue(request, OWNER)).isEqualTo(ID);
        verify(queue, never()).countPending();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void enqueueRequiresAModel(String model) {
        assertThatThrownBy(() -> service().enqueue(request(model, "{}"), OWNER))
                .isInstanceOf(InvalidRequestException.class).hasMessage("model is required");
    }

    @Test
    void enqueueRejectsOverlongModelNames() {
        assertThatThrownBy(() -> service().enqueue(request("m".repeat(201), "{}"), OWNER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void enqueueRejectsBodiesOverTheByteLimit() {
        // Two-byte characters: 6 characters but 12 bytes.
        assertThatThrownBy(() -> service(1, 350, 0, 10).enqueue(request("m", "éééééé"), OWNER))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void enqueueAppliesTheQueueLimit() {
        when(queue.countPending()).thenReturn(2L);
        assertThatThrownBy(() -> service(1, 350, 2, 1024).enqueue(request("m", "{}"), OWNER))
                .isInstanceOf(QueueFullException.class);
        when(queue.submit(any(), anyString())).thenReturn(ID);
        assertThat(service(1, 350, 3, 1024).enqueue(request("m", "{}"), OWNER)).isEqualTo(ID);
    }

    @Test
    void submitAndAwaitReturnsAFinishedResultWithoutSleeping() throws InterruptedException {
        when(queue.submit(any(), anyString())).thenReturn(ID);
        when(queue.find(ID, OWNER)).thenReturn(Optional.of(DONE));
        assertThat(service().submitAndAwait(request("m", "{}"), OWNER)).isEqualTo(new InferenceOutcome(ID, DONE));
        assertThat(clock.sleeps).isEmpty();
    }

    @Test
    void submitAndAwaitPollsWithAMinimumInterval() throws InterruptedException {
        when(queue.submit(any(), anyString())).thenReturn(ID);
        when(queue.find(ID, OWNER)).thenReturn(Optional.of(PENDING), Optional.of(DONE));
        assertThat(service(1, 10, 0, 1024).submitAndAwait(request("m", "{}"), OWNER).result()).isEqualTo(DONE);
        assertThat(clock.sleeps).containsExactly(Duration.ofMillis(50));
    }

    @Test
    void submitAndAwaitReturnsThePendingStateAfterTheDeadline() throws InterruptedException {
        when(queue.submit(any(), anyString())).thenReturn(ID);
        when(queue.find(ID, OWNER)).thenReturn(Optional.of(PENDING));
        InferenceOutcome outcome = service(1, 400, 0, 1024).submitAndAwait(request("m", "{}"), OWNER);
        assertThat(outcome.result().isFinished()).isFalse();
        assertThat(clock.sleeps).hasSize(3);
    }

    @Test
    void findResultOfUnknownOrForeignRequestIsNotFound() {
        when(queue.find(ID, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().findResult(ID, OWNER))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Unknown request");
    }

    @Test
    void availableModelsComeFromTheQueue() {
        when(queue.availableModels()).thenReturn(List.of("a", "b"));
        assertThat(service().availableModels()).containsExactly("a", "b");
    }
}
