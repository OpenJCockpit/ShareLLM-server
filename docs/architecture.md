# Architecture

## Request flow

```text
agent / Embabel ─► LiteLLM ─► /v1/chat/completions or /api/chat
                                   │
                                   ▼
                     llm_jobs (READY)  ── indexed, PostgreSQL
                                   │
                    SELECT … FOR UPDATE SKIP LOCKED
                                   ▼
                     llm_jobs (LEASED; worker, UUID lease token)
                                   │
                outbound HTTPS poll from a laptop, local Ollama call
                                   │
                                   ▼
                     /internal/jobs/{id}/complete
                                   │
                                   ▼
                     llm_results (durable)
                                   │
                                   ▼
             original HTTP caller, or GET /sharellm/requests/{id}
```

## Durable queues and leasing

- **Registration and heartbeats.** Workers register their model lists, send a heartbeat every 10
  seconds and long-poll `GET /internal/jobs/claim?clientId=…&waitSeconds=15`. The heartbeat extends
  the worker's lease, even during a long model call.
- **Atomic claims.** Claims use PostgreSQL `SKIP LOCKED`. A worker can hold **one lease at a time**,
  also enforced by a partial unique index. Every claim gets a random `leaseToken`; writes with an
  older token get `409`.
- **Recovery.** The scheduler puts leased work back to `READY` when the worker's **heartbeat
  expires** (default 35 s) or the **lease deadline passes** (default 45 s), so another laptop can
  claim it. A job gets up to `MAX_ATTEMPTS` (default 3) attempts; a final failure is stored in
  `llm_results` for the owner.
- **No in-process state.** Jobs and results live in PostgreSQL. Synchronous callers wait on virtual
  threads that poll the database, so any instance can deliver a result.
  See [scaling.md](scaling.md).
- **At-least-once processing.** A prompt can run more than once when a worker disconnects. Only the
  current lease token can publish a result, so duplicates are never accepted, but the inference
  itself may be repeated. A timeout does not mean the prompt was cancelled.
- **Retention.** Results and finished jobs are removed after `RESULT_RETENTION_HOURS` (default 24).

## Code structure

The server follows a clean (hexagonal) architecture. Dependencies only point inwards, which
`ArchitectureTest` enforces with ArchUnit.

| Package (`io.sharellm.server.…`) | Responsibility |
|---|---|
| `domain` | Plain-Java model (`Job`, `JobResult`, `InferenceEndpoint`, …) and the exception types |
| `application` | Use cases (`port.inbound`), the ports they need (`port.outbound`), their framework-free implementations (`service`) and settings |
| `adapter.inbound.web` | REST controllers for the Ollama, OpenAI, async-request and worker APIs, plus HTTP error mapping |
| `adapter.inbound.scheduling` | Periodic lease reclaim and cleanup |
| `adapter.outbound.persistence` | The PostgreSQL queue (`PostgresJobRepository`) |
| `infrastructure.config` | Configuration properties and wiring of the application services |

The database schema is in [`schema.sql`](../src/main/resources/schema.sql).
