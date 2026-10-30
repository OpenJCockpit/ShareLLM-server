package io.sharellm.server.adapter.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sharellm.server.PostgresIntegrationTest;
import io.sharellm.server.domain.exception.LeaseConflictException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.JobResult;
import io.sharellm.server.domain.model.JobState;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Exercises the SQL against PostgreSQL; time is simulated by moving timestamps into the past. */
class PostgresJobRepositoryTest extends PostgresIntegrationTest {
    private static final String OWNER = "owner";
    private static final InferenceRequest CHAT =
            new InferenceRequest(InferenceEndpoint.CHAT, "llama3", "{\"model\":\"llama3\",\"messages\":[]}");

    @Autowired PostgresJobRepository repository;

    private Job leasedJob(String clientId) {
        repository.register(clientId, List.of("llama3"));
        repository.submit(CHAT, OWNER);
        return repository.claim(clientId).orElseThrow();
    }

    private Map<String, Object> jobRow(UUID id) {
        return db.queryForMap("SELECT state, attempts, lease_owner, last_error, available_at > now() AS delayed "
                + "FROM llm_jobs WHERE id=?", id);
    }

    private void ageWorker(String clientId) {
        db.update("UPDATE llm_workers SET last_seen=now()-interval '1 hour' WHERE client_id=?", clientId);
    }

    private void setAttempts(UUID id, int attempts) {
        db.update("UPDATE llm_jobs SET attempts=? WHERE id=?", attempts, id);
    }

    private JobResult result(UUID id) { return repository.find(id, OWNER).orElseThrow(); }

    @Test
    void submittedJobsAreReadyAndVisibleOnlyToTheirOwner() {
        UUID id = repository.submit(CHAT, OWNER);
        assertThat(result(id)).isEqualTo(new JobResult(id, "chat", "llama3", JobState.READY, null, null));
        assertThat(repository.find(id, "someone-else")).isEmpty();
        assertThat(repository.countPending()).isEqualTo(1);
    }

    @Test
    void availableModelsComeFromLiveWorkersOnly() {
        repository.register("live", List.of("b", "a"));
        repository.register("gone", List.of("c"));
        ageWorker("gone");
        assertThat(repository.availableModels()).containsExactly("a", "b");
    }

    @Test
    void claimLeasesTheOldestMatchingJobOnce() {
        repository.register("laptop", List.of("llama3"));
        UUID other = repository.submit(new InferenceRequest(InferenceEndpoint.CHAT, "mistral", "{}"), OWNER);
        UUID first = repository.submit(CHAT, OWNER);
        repository.submit(CHAT, OWNER);

        Job job = repository.claim("laptop").orElseThrow();
        assertThat(job.id()).isEqualTo(first);
        assertThat(job.endpoint()).isEqualTo("chat");
        assertThat(job.payload()).contains("\"messages\"");
        assertThat(job.attempts()).isZero();
        assertThat(result(first).state()).isEqualTo(JobState.LEASED);
        // One lease per worker, and never a model the worker does not advertise.
        assertThat(repository.claim("laptop")).isEmpty();
        assertThat(result(other).state()).isEqualTo(JobState.READY);
    }

    @Test
    void staleOrUnknownWorkersCannotClaim() {
        repository.submit(CHAT, OWNER);
        repository.register("laptop", List.of("llama3"));
        ageWorker("laptop");
        assertThat(repository.claim("laptop")).isEmpty();
        assertThat(repository.claim("unknown")).isEmpty();
    }

    @Test
    void completeStoresTheResultForTheCurrentLeaseOnly() {
        Job job = leasedJob("laptop");
        assertThatThrownBy(() -> repository.complete(job.id(), "laptop", UUID.randomUUID(), "{}"))
                .isInstanceOf(LeaseConflictException.class).hasMessage("Stale or lost lease");

        repository.complete(job.id(), "laptop", job.leaseToken(), "{\"done\":true}");
        JobResult result = result(job.id());
        assertThat(result.state()).isEqualTo(JobState.COMPLETED);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("{\"done\": true}");
        assertThat(repository.countPending()).isZero();
        // A duplicate completion with the same token is fenced.
        assertThatThrownBy(() -> repository.complete(job.id(), "laptop", job.leaseToken(), "{}"))
                .isInstanceOf(LeaseConflictException.class);
    }

    @Test
    void failRequeuesWithBackoffUntilTheRetryBudgetIsSpent() {
        Job job = leasedJob("laptop");
        repository.fail(job.id(), "laptop", job.leaseToken(), null);
        assertThat(jobRow(job.id())).containsEntry("state", "READY").containsEntry("attempts", 1)
                .containsEntry("last_error", "unknown").containsEntry("delayed", true);
        assertThat(result(job.id()).isFinished()).isFalse();

        db.update("UPDATE llm_jobs SET available_at=now() WHERE id=?", job.id());
        Job retry = repository.claim("laptop").orElseThrow();
        setAttempts(job.id(), 2);
        repository.fail(job.id(), "laptop", retry.leaseToken(), "x".repeat(2000));
        assertThat(jobRow(job.id())).containsEntry("state", "FAILED");
        assertThat((String) jobRow(job.id()).get("last_error")).hasSize(PostgresJobRepository.MAX_STORED_ERROR_LENGTH);
        JobResult result = result(job.id());
        assertThat(result.httpStatus()).isEqualTo(502);
        assertThat(result.body()).contains("Local inference failed after retries: xxx");
    }

    @Test
    void failWithAStaleLeaseConflicts() {
        Job job = leasedJob("laptop");
        assertThatThrownBy(() -> repository.fail(job.id(), "laptop", UUID.randomUUID(), "e"))
                .isInstanceOf(LeaseConflictException.class);
    }

    @Test
    void heartbeatRequiresRegistrationAndExtendsLiveLeases() {
        assertThatThrownBy(() -> repository.heartbeat("never-registered"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Register worker first");

        Job job = leasedJob("laptop");
        db.update("UPDATE llm_jobs SET lease_deadline=now()+interval '1 second' WHERE id=?", job.id());
        repository.heartbeat("laptop");
        Boolean extended = db.queryForObject(
                "SELECT lease_deadline > now()+interval '30 seconds' FROM llm_jobs WHERE id=?", Boolean.class, job.id());
        assertThat(extended).isTrue();
    }

    @Test
    void heartbeatAfterExpiryRequeuesTheOldLease() {
        Job job = leasedJob("laptop");
        ageWorker("laptop");
        repository.heartbeat("laptop");
        assertThat(jobRow(job.id())).containsEntry("state", "READY").containsEntry("attempts", 1)
                .containsEntry("last_error", "Worker heartbeat expired");
        assertThatThrownBy(() -> repository.complete(job.id(), "laptop", job.leaseToken(), "{}"))
                .isInstanceOf(LeaseConflictException.class);
    }

    @Test
    void reRegistrationAfterExpiryFailsJobsWithoutRetriesLeft() {
        Job job = leasedJob("laptop");
        setAttempts(job.id(), 2);
        ageWorker("laptop");
        repository.register("laptop", List.of("llama3", "mistral"));
        assertThat(jobRow(job.id())).containsEntry("state", "FAILED");
        assertThat(result(job.id()).body()).contains("Worker lost; retry budget exhausted");
        assertThat(repository.availableModels()).containsExactly("llama3", "mistral");
    }

    @Test
    void reRegistrationOfALiveWorkerKeepsItsLease() {
        Job job = leasedJob("laptop");
        repository.register("laptop", List.of("llama3"));
        assertThat(jobRow(job.id())).containsEntry("state", "LEASED");
    }

    @Test
    void reclaimRequeuesExpiredLeasesAndFailsExhaustedOnes() {
        Job expired = leasedJob("laptop-1");
        db.update("UPDATE llm_jobs SET lease_deadline=now()-interval '1 second' WHERE id=?", expired.id());
        Job lost = leasedJob("laptop-2");
        setAttempts(lost.id(), 2);
        ageWorker("laptop-2");
        Job healthy = leasedJob("laptop-3");

        repository.reclaimExpiredLeases();

        assertThat(jobRow(expired.id())).containsEntry("state", "READY")
                .containsEntry("last_error", "Worker missed heartbeat or lease expired");
        assertThat(jobRow(lost.id())).containsEntry("state", "FAILED");
        assertThat(result(lost.id()).httpStatus()).isEqualTo(502);
        assertThat(jobRow(healthy.id())).containsEntry("state", "LEASED");
    }

    @Test
    void purgeRemovesOldResultsAndLongGoneWorkers() {
        Job old = leasedJob("laptop");
        repository.complete(old.id(), "laptop", old.leaseToken(), "{}");
        db.update("UPDATE llm_results SET completed_at=now()-interval '25 hours' WHERE job_id=?", old.id());
        UUID waiting = repository.submit(CHAT, OWNER);
        repository.register("retired", List.of("m"));
        db.update("UPDATE llm_workers SET last_seen=now()-interval '8 days' WHERE client_id='retired'");

        repository.purgeExpired();

        assertThat(repository.find(old.id(), OWNER)).isEmpty();
        assertThat(repository.find(waiting, OWNER)).isPresent();
        assertThat(db.queryForObject("SELECT count(*) FROM llm_workers", Long.class)).isEqualTo(1);
    }
}
