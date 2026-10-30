package io.sharellm.server.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.sharellm.server.application.port.outbound.WorkerLeases;
import io.sharellm.server.application.settings.QueueSettings;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.WorkerRegistration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerServiceTest {
    private static final Job JOB = new Job(UUID.randomUUID(), "chat", "m", "{}", UUID.randomUUID(), 0);

    private final WorkerLeases leases = mock(WorkerLeases.class);
    private final FakeClock clock = new FakeClock();
    private final WorkerService service =
            new WorkerService(leases, new QueueSettings(35, 45, 3, 300, 1, 350, 24, 0, 1024), clock);

    @Test
    void registerDeduplicatesModelsInOrder() {
        WorkerRegistration registration = service.register("laptop-1", List.of("b", "a", "b"));
        verify(leases).register("laptop-1", List.of("b", "a"));
        assertThat(registration).isEqualTo(new WorkerRegistration("laptop-1", 10, 45));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "has space", "slash/", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}) // the last ID is 81 characters
    void rejectsInvalidClientIds(String clientId) {
        assertThatThrownBy(() -> service.register(clientId, List.of("m")))
                .isInstanceOf(InvalidRequestException.class).hasMessage("invalid clientId");
        assertThatThrownBy(() -> service.heartbeat(clientId)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.claim(clientId, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.complete(JOB.id(), clientId, JOB.leaseToken(), "{}"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.fail(JOB.id(), clientId, JOB.leaseToken(), "e"))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(leases);
    }

    @Test
    void rejectsInvalidModelLists() {
        List<String> withNull = new ArrayList<>(Arrays.asList("ok", null));
        for (List<String> models : List.of(withNull, List.of(" "), List.of("m".repeat(201)),
                Collections.nCopies(201, "m"))) {
            assertThatThrownBy(() -> service.register("laptop", models))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessage("models must be a list of at most 200 model names");
        }
        assertThatThrownBy(() -> service.register("laptop", null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(leases);
    }

    @Test
    void heartbeatCompleteAndFailDelegate() {
        service.heartbeat("laptop");
        service.complete(JOB.id(), "laptop", JOB.leaseToken(), "{\"done\":true}");
        service.fail(JOB.id(), "laptop", JOB.leaseToken(), "boom");
        verify(leases).heartbeat("laptop");
        verify(leases).complete(JOB.id(), "laptop", JOB.leaseToken(), "{\"done\":true}");
        verify(leases).fail(JOB.id(), "laptop", JOB.leaseToken(), "boom");
    }

    @Test
    void claimReturnsAnAvailableJobImmediately() throws InterruptedException {
        when(leases.claim("laptop")).thenReturn(Optional.of(JOB));
        assertThat(service.claim("laptop", 15)).contains(JOB);
        assertThat(clock.sleeps).isEmpty();
    }

    @Test
    void claimRetriesUntilAJobArrives() throws InterruptedException {
        when(leases.claim("laptop")).thenReturn(Optional.empty(), Optional.of(JOB));
        assertThat(service.claim("laptop", 15)).contains(JOB);
        assertThat(clock.sleeps).containsExactly(WorkerService.CLAIM_RETRY_DELAY);
    }

    @Test
    void claimWaitIsCappedByThePollWindow() throws InterruptedException {
        when(leases.claim(any())).thenReturn(Optional.empty());
        // 1 s window, 400 ms retries: attempts at 0, 400, 800 and 1200 ms.
        assertThat(service.claim("laptop", 100)).isEmpty();
        verify(leases, times(4)).claim("laptop");
    }

    @Test
    void negativeWaitMeansASingleAttempt() throws InterruptedException {
        when(leases.claim(any())).thenReturn(Optional.empty());
        assertThat(service.claim("laptop", -5)).isEmpty();
        verify(leases, times(1)).claim("laptop");
    }
}
