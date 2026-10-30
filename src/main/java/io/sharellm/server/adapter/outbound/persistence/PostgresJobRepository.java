package io.sharellm.server.adapter.outbound.persistence;

import io.sharellm.server.application.port.outbound.InferenceQueue;
import io.sharellm.server.application.port.outbound.QueueMaintenance;
import io.sharellm.server.application.port.outbound.WorkerLeases;
import io.sharellm.server.application.settings.QueueSettings;
import io.sharellm.server.domain.exception.LeaseConflictException;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.JobResult;
import io.sharellm.server.domain.model.JobState;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Postgres serves as both a durable pending queue and a durable completion queue.
 * An atomic SKIP LOCKED claim is the only way to grant a lease. */
@Repository
public class PostgresJobRepository implements InferenceQueue, WorkerLeases, QueueMaintenance {
    static final int MAX_STORED_ERROR_LENGTH = 1500;
    static final int MAX_REPORTED_ERROR_LENGTH = 300;

    private static final String LOCK_WORKER_STALENESS = """
        SELECT last_seen <= now()-(? * interval '1 second') AS stale
        FROM llm_workers WHERE client_id=? FOR UPDATE
        """;

    private final JdbcTemplate db;
    private final QueueSettings settings;

    public PostgresJobRepository(JdbcTemplate db, QueueSettings settings) {
        this.db = db;
        this.settings = settings;
    }

    @Override
    public UUID submit(InferenceRequest request, String ownerHash) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO llm_jobs(id,endpoint,model,payload,owner_hash) VALUES (?,?,?,?::jsonb,?)",
                id, request.endpoint().wireName(), request.model(), request.payloadJson(), ownerHash);
        return id;
    }

    @Override
    public long countPending() {
        Long pending = db.queryForObject("SELECT count(*) FROM llm_jobs WHERE state IN ('READY','LEASED')", Long.class);
        return pending == null ? 0 : pending;
    }

    @Override
    public Optional<JobResult> find(UUID id, String ownerHash) {
        return db.query("""
            SELECT j.id,j.endpoint,j.model,j.state,r.body::text AS body,r.http_status
            FROM llm_jobs j LEFT JOIN llm_results r ON r.job_id=j.id
            WHERE j.id=? AND j.owner_hash=?
            """, (rs, n) -> new JobResult((UUID) rs.getObject("id"), rs.getString("endpoint"),
                rs.getString("model"), JobState.valueOf(rs.getString("state")), rs.getString("body"),
                (Integer) rs.getObject("http_status")), id, ownerHash).stream().findFirst();
    }

    @Override
    public List<String> availableModels() {
        return db.query("""
            SELECT DISTINCT unnest(models) AS name FROM llm_workers
            WHERE last_seen > now()-(? * interval '1 second') ORDER BY name
            """, (rs, i) -> rs.getString("name"), settings.heartbeatTimeoutSeconds());
    }

    @Override
    @Transactional
    public void register(String clientId, List<String> models) {
        // A returning laptop after its heartbeat expired must not revive old leases.
        if (lockWorkerStaleness(clientId).orElse(false)) expireLeasesOf(clientId);
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO llm_workers(client_id, models, last_seen) VALUES (?, ?, now())
                ON CONFLICT (client_id) DO UPDATE SET models=EXCLUDED.models, last_seen=now()
                """);
            ps.setString(1, clientId);
            ps.setArray(2, connection.createArrayOf("text", models.toArray(String[]::new)));
            return ps;
        });
    }

    @Override
    @Transactional
    public void heartbeat(String clientId) {
        boolean stale = lockWorkerStaleness(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Register worker first"));
        if (stale) expireLeasesOf(clientId);
        db.update("UPDATE llm_workers SET last_seen=now() WHERE client_id=?", clientId);
        // Only unexpired leases can be extended; expired or stale work is requeued.
        db.update("""
            UPDATE llm_jobs SET lease_deadline=now()+(? * interval '1 second')
            WHERE state='LEASED' AND lease_owner=? AND lease_deadline>now()
            """, settings.leaseSeconds(), clientId);
    }

    @Override
    public Optional<Job> claim(String clientId) {
        UUID leaseToken = UUID.randomUUID();
        try {
            return db.query("""
                WITH picked AS (
                  SELECT j.id FROM llm_jobs j
                  JOIN llm_workers w ON w.client_id=?
                  WHERE w.last_seen > now()-(? * interval '1 second')
                    AND j.state='READY' AND j.available_at <= now()
                    AND j.model = ANY(w.models)
                    AND NOT EXISTS (SELECT 1 FROM llm_jobs other
                        WHERE other.lease_owner=w.client_id AND other.state='LEASED')
                  ORDER BY j.created_at
                  LIMIT 1
                  FOR UPDATE OF j SKIP LOCKED
                )
                UPDATE llm_jobs j
                SET state='LEASED', lease_owner=?, lease_token=?,
                    lease_deadline=now()+(? * interval '1 second')
                FROM picked WHERE j.id=picked.id
                RETURNING j.id,j.endpoint,j.model,j.payload::text AS payload,j.lease_token,j.attempts
                """, (rs, n) -> new Job((UUID) rs.getObject("id"), rs.getString("endpoint"),
                    rs.getString("model"), rs.getString("payload"),
                    (UUID) rs.getObject("lease_token"), rs.getInt("attempts")),
                    clientId, settings.heartbeatTimeoutSeconds(), clientId, leaseToken, settings.leaseSeconds())
                .stream().findFirst();
        } catch (DuplicateKeyException concurrentPoll) {
            // A second poll for the same worker raced; the unique lease index fences it.
            return Optional.empty();
        }
    }

    /** Only the current holder of the current fencing token can write a result. */
    @Override
    @Transactional
    public void complete(UUID jobId, String clientId, UUID leaseToken, String responseJson) {
        int claimed = db.update("""
            UPDATE llm_jobs SET state='COMPLETED',lease_deadline=NULL
            WHERE id=? AND state='LEASED' AND lease_owner=? AND lease_token=?
              AND EXISTS (SELECT 1 FROM llm_workers w WHERE w.client_id=?
                AND w.last_seen > now()-(? * interval '1 second'))
              AND lease_deadline > now()
            """, jobId, clientId, leaseToken, clientId, settings.heartbeatTimeoutSeconds());
        if (claimed == 0) throw new LeaseConflictException("Stale or lost lease");
        db.update("INSERT INTO llm_results(job_id,success,http_status,body) VALUES (?,true,200,?::jsonb)",
                jobId, responseJson);
    }

    @Override
    @Transactional
    public void fail(UUID jobId, String clientId, UUID leaseToken, String error) {
        List<Integer> attempts = db.query("""
            UPDATE llm_jobs SET attempts=attempts+1,
              state=CASE WHEN attempts+1 >= ? THEN 'FAILED' ELSE 'READY' END,
              lease_owner=NULL,lease_token=NULL,lease_deadline=NULL,
              available_at=now()+(LEAST(attempts+1,5) * interval '1 second'),
              last_error=?
            WHERE id=? AND state='LEASED' AND lease_owner=? AND lease_token=?
              AND lease_deadline > now()
            RETURNING attempts
            """, (rs, i) -> rs.getInt(1), settings.maxAttempts(),
                truncate(error, MAX_STORED_ERROR_LENGTH), jobId, clientId, leaseToken);
        if (attempts.isEmpty()) throw new LeaseConflictException("Stale or lost lease");
        if (attempts.getFirst() >= settings.maxAttempts()) {
            db.update("""
                INSERT INTO llm_results(job_id,success,http_status,body)
                VALUES (?,false,502,jsonb_build_object('error', ?))
                """, jobId, "Local inference failed after retries: " + truncate(error, MAX_REPORTED_ERROR_LENGTH));
        }
    }

    @Override
    @Transactional
    public void reclaimExpiredLeases() {
        db.update("""
            WITH expired AS (
              UPDATE llm_jobs j
              SET attempts=attempts+1,
                state=CASE WHEN attempts+1 >= ? THEN 'FAILED' ELSE 'READY' END,
                lease_owner=NULL,lease_token=NULL,lease_deadline=NULL,
                available_at=now(),last_error='Worker missed heartbeat or lease expired'
              WHERE j.state='LEASED' AND (
                 j.lease_deadline <= now() OR NOT EXISTS (
                   SELECT 1 FROM llm_workers w WHERE w.client_id=j.lease_owner
                   AND w.last_seen > now()-(? * interval '1 second')
                 )
              )
              RETURNING id,state
            )
            INSERT INTO llm_results(job_id,success,http_status,body)
            SELECT id,false,502,'{"error":"Worker lost; retry budget exhausted"}'::jsonb
            FROM expired WHERE state='FAILED'
            ON CONFLICT(job_id) DO NOTHING
            """, settings.maxAttempts(), settings.heartbeatTimeoutSeconds());
    }

    @Override
    public void purgeExpired() {
        db.update("""
            DELETE FROM llm_jobs j USING llm_results r
            WHERE j.id=r.job_id AND r.completed_at < now()-(? * interval '1 hour')
            """, settings.retentionHours());
        db.update("DELETE FROM llm_workers WHERE last_seen < now()-interval '7 days'");
    }

    /** Locks the worker row; empty if the worker never registered. */
    private Optional<Boolean> lockWorkerStaleness(String clientId) {
        return db.query(LOCK_WORKER_STALENESS, (rs, i) -> rs.getBoolean("stale"),
                settings.heartbeatTimeoutSeconds(), clientId).stream().findFirst();
    }

    private void expireLeasesOf(String clientId) {
        db.update("""
            WITH expired AS (
              UPDATE llm_jobs SET attempts=attempts+1,
                state=CASE WHEN attempts+1 >= ? THEN 'FAILED' ELSE 'READY' END,
                lease_owner=NULL,lease_token=NULL,lease_deadline=NULL,available_at=now(),
                last_error='Worker heartbeat expired'
              WHERE state='LEASED' AND lease_owner=?
              RETURNING id,state
            )
            INSERT INTO llm_results(job_id,success,http_status,body)
            SELECT id,false,502,'{"error":"Worker lost; retry budget exhausted"}'::jsonb
            FROM expired WHERE state='FAILED'
            ON CONFLICT(job_id) DO NOTHING
            """, settings.maxAttempts(), clientId);
    }

    private static String truncate(String value, int max) {
        return value == null ? "unknown" : value.substring(0, Math.min(value.length(), max));
    }
}
