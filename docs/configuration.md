# Configuration

The server is configured with environment variables. With Docker Compose, set them in `.env`; see
[`.env.example`](../.env.example) for development and
[`deploy/production.env.example`](../deploy/production.env.example) for production.

| Variable | Default | Purpose |
|---|---|---|
| `SHARELLM_PUBLIC_KEYS` | **required** | Comma-separated owner keys for callers |
| `SHARELLM_WORKER_KEY` | **required** | Worker key; must differ from every owner key |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | local development PostgreSQL | Database connection |
| `PORT` | `8080` | API port (TLS is terminated by the proxy or ingress) |
| `MANAGEMENT_PORT` | `8081` | Health probes; keep internal |
| `SHARELLM_SECRETS_DIR` | `/run/secrets` | Directory of secret files; a file named like a variable sets it |
| `SHUTDOWN_TIMEOUT` | `30s` | Graceful shutdown limit for running requests |
| `REQUEST_WAIT_SECONDS` | `300` | Maximum wait of a synchronous call |
| `HEARTBEAT_TIMEOUT_SECONDS` | `35` | When a silent worker counts as lost |
| `LEASE_SECONDS` | `45` | Initial and extended lease duration |
| `MAX_ATTEMPTS` | `3` | Claims per job before it fails |
| `WORKER_POLL_SECONDS` | `15` | Maximum long-poll window for workers |
| `RESULT_POLL_MILLIS` | `350` | How often waiting callers check the database |
| `RESULT_RETENTION_HOURS` | `24` | How long results and finished jobs are kept |
| `DB_POOL_SIZE` | `24` | Database connections per instance |
| `QUEUE_LIMIT` | `0` (off) | Rejects new jobs with `429` above this many pending jobs (adds a `COUNT` per job) |
| `MAX_BODY_BYTES` | `1048576` | Maximum request body size |

The server refuses to start without both keys, or when the worker key equals an owner key.

## Security notes

- Use TLS, long random keys (`openssl rand -hex 32`), network segmentation, database backups and
  encryption, and rate limiting at the ingress. The [production setup](deployment.md) does most of
  this for you.
- **The worker key is a shared secret**, not a per-worker identity: whoever holds it can act as any
  worker and read the prompts sent to workers. Only install it on trusted, consenting laptops, and
  don't route secrets or private prompts to laptops you don't trust.
- Results are isolated per owner key, but all callers that share an owner key share access to each
  other's results. Give each tenant its own key, and use a gateway with central identity and
  authorization for stronger multi-tenant isolation.

See also [SECURITY.md](../SECURITY.md).
