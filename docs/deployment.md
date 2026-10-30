# Production deployment

This guide deploys ShareLLM Server on a single Linux host with Docker Compose, with automatic HTTPS.
For local development see [development.md](development.md).

## What gets deployed

```text
 LiteLLM / agents ─┐                       ┌─────────────── host ────────────────────────────┐
                   ├── HTTPS :443 ────────►│ caddy ──► sharellm:8080 ──► postgres:5432        │
 laptop workers ───┘   (HTTP :80 redirects)│  edge network      backend network (internal)    │
                                           └──────────────────────────────────────────────────┘
```

| Service | Role | Exposed |
|---|---|---|
| `caddy` | TLS termination with automatic Let's Encrypt certificates, HTTP→HTTPS redirect, security headers, request size limit | `80`, `443` (TCP and UDP) |
| `sharellm` | The server: API on `8080`, health probes on `8081` | Nothing; reachable only through Caddy |
| `postgres` | Durable job and result queues | Nothing; on an internal network without outside access |

Production settings in `compose.prod.yaml`:

- **Secrets:** keys and the database password are Docker secrets (files under `secrets/`), not
  environment variables, so they don't show up in `docker inspect` or process listings.
- **Hardening:** the server runs as UID 10001 with a read-only root filesystem, all Linux
  capabilities dropped and `no-new-privileges`. Caddy keeps only `NET_BIND_SERVICE`.
- **Resource limits:** memory and CPU limits, and JSON logs rotated at 5 × 20 MB per container.
- **Structured logs:** the server logs in ECS JSON format.
- **Restarts and shutdown:** `restart: unless-stopped`, and a graceful shutdown of up to 30 s.
- **Startup checks:** the server refuses to start when a key is missing, or when the worker key
  equals an owner key.

## Requirements

- A Linux host with Docker Engine and Docker Compose v2. 2 vCPU and 2 GB RAM is a reasonable start;
  the server does no inference itself.
- A DNS name (for example `sharellm.example.org`) with an A/AAAA record pointing at the host.
- Inbound TCP 80 and TCP/UDP 443 open. Port 80 is needed for Let's Encrypt and redirects.
- Outbound HTTPS to Let's Encrypt, and to your registry if you pull images.
- `openssl`, to generate secrets.

## Install

```bash
git clone https://github.com/OpenJCockpit/ShareLLM-server.git /opt/sharellm
cd /opt/sharellm

cp deploy/production.env.example .env     # sets COMPOSE_FILE=compose.yaml:compose.prod.yaml
${EDITOR:-vi} .env                         # set SHARELLM_DOMAIN and ACME_EMAIL

./deploy/generate-secrets.sh               # creates secrets/ with random keys
docker compose up --build -d
docker compose ps                          # all services "healthy" / "running"
```

Check the deployment from another machine:

```bash
curl -s https://sharellm.example.org/health          # {"status":"UP"}
curl -sI http://sharellm.example.org/health          # 308 redirect to HTTPS
curl -s https://sharellm.example.org/api/tags \
  -H "Authorization: Bearer $(cat secrets/sharellm_public_keys)"
```

To run the bundled test scripts against production, use the keys from `secrets/` and
`SHARELLM_URL=https://sharellm.example.org`. The smoke test creates and completes one test job.

## Secrets

`deploy/generate-secrets.sh` creates three files with 256-bit random values and keeps existing files:

| File | Used as | Give it to |
|---|---|---|
| `secrets/sharellm_public_keys` | `SHARELLM_PUBLIC_KEYS` (owner keys, comma-separated) | LiteLLM, agents and other callers |
| `secrets/sharellm_worker_key` | `SHARELLM_WORKER_KEY` | Laptop workers |
| `secrets/database_password` | `DATABASE_PASSWORD` and the PostgreSQL password | Nobody |

The directory is `700` and the files are `644`. Docker Compose mounts secret files with their host
permissions, and the containers run as non-root users, so the files must stay readable inside the
directory.

The server reads every file in `/run/secrets` (configurable with `SHARELLM_SECRETS_DIR`) as a
configuration value named after the file. Kubernetes or another orchestrator can mount secrets the
same way.

### Rotating keys

- **Owner keys.** Several comma-separated owner keys are allowed, so you can rotate without
  downtime:
  1. Append the new key: `printf ',%s' "$(openssl rand -hex 32)" >> secrets/sharellm_public_keys`.
  2. Restart the server: `docker compose up -d --force-recreate sharellm`.
  3. Move the callers to the new key.
  4. Remove the old key and restart again.

  Results can only be fetched with the key that submitted them, so let pending requests finish first.
- **Worker key.** There is one shared worker key: replace the file, restart, and update every laptop
  at the same time. Workers that still use the old key get `401` until they are updated.
- **Database password.** The bundled PostgreSQL only reads its password when the volume is first
  created. To change it later, run `ALTER USER sharellm PASSWORD '...'` with
  `docker compose exec postgres psql -U sharellm`, then update the file and restart `sharellm`.

## Configuration

All settings go in `.env`; `deploy/production.env.example` lists them with their defaults. The most
important ones:

| Variable | Default | Notes |
|---|---|---|
| `SHARELLM_DOMAIN`, `ACME_EMAIL` | required | Public name and Let's Encrypt contact |
| `REQUEST_WAIT_SECONDS` | `300` | How long synchronous calls wait for a worker |
| `UPSTREAM_RESPONSE_TIMEOUT` | `330s` | Proxy wait for the server; must exceed `REQUEST_WAIT_SECONDS` |
| `MAX_BODY_BYTES` / `MAX_REQUEST_BODY` | `1048576` / `2MB` | Server and proxy body limits; keep the proxy limit higher |
| `QUEUE_LIMIT` | `0` (off) | Rejects new jobs with `429` above this many pending jobs |
| `SHARELLM_MEMORY_LIMIT`, `SHARELLM_CPU_LIMIT` | `1g`, `2` | Container limits; the JVM uses 75% of the memory limit for its heap |
| `POSTGRES_MEMORY_LIMIT` | `1g` | Container limit for the bundled database |
| `SHARELLM_IMAGE` | `sharellm-server:local` | Set a registry tag to run a prebuilt image |

Configure LiteLLM's `timeout` above `REQUEST_WAIT_SECONDS` as well; see [integrations.md](integrations.md#litellm-recommended).

### Using an external PostgreSQL

To use a managed database (recommended when you need high availability or point-in-time recovery),
set `SHARELLM_DATABASE_URL` (with `sslmode=verify-full` where supported) and `SHARELLM_DATABASE_USER`
in `.env`, and put that user's password in `secrets/database_password`.

The bundled `postgres` service still starts. The `backend` network is internal, so the server also
needs a route to the external database: remove `internal: true` from `compose.prod.yaml`, or attach a
network that can reach it. Unless you do, the server cannot connect.

## Operations

### Health and monitoring

| Endpoint | Meaning |
|---|---|
| `https://<domain>/health` | The process answers; suitable for external uptime checks |
| `:8081/actuator/health/liveness` | Liveness probe (inside the host or cluster only) |
| `:8081/actuator/health/readiness` | Readiness probe, includes the database; used by the container health check |

Port 8081 is not exposed. To query it on the host, run
`docker compose exec sharellm wget -qO- http://127.0.0.1:8081/actuator/health/readiness`.

### Logs

```bash
docker compose logs -f sharellm          # ECS JSON, one event per line
docker compose logs -f caddy             # access log in JSON
```

Ship the container logs with your usual collector (Docker logging driver, Vector, Fluent Bit, …).

### Upgrading

```bash
cd /opt/sharellm
git pull
docker compose up --build -d             # rebuilds the image and replaces the server container
```

Run `mvn -B verify` in CI before you deploy, because the image build does not run the tests. To
deploy prebuilt images instead:

1. Build and push from CI: `docker build -t registry.example.org/sharellm-server:<version> .`.
2. Set `SHARELLM_IMAGE` in `.env`.
3. Run `docker compose pull sharellm && docker compose up -d --no-build`.

What happens during the restart:

- **Callers waiting synchronously** are disconnected after at most 30 s. Their jobs stay in the
  database, so they can poll `/sharellm/requests/{id}`.
- **Workers** reconnect on their own. Leases whose heartbeat expired while the server was down are
  requeued and run again, because processing is at-least-once.

`schema.sql` only creates missing tables and indexes. There is no migration tool yet, so read the
release notes before an upgrade that changes the schema.

**Rolling back:** set `SHARELLM_IMAGE` to the previous tag, or check out the previous commit, and
run `docker compose up -d` again.

### Backups

Jobs and results are short-lived (`RESULT_RETENTION_HOURS`, 24 h by default), but a backup protects
queued and unread results:

```bash
docker compose exec -T postgres pg_dump -U sharellm -Fc sharellm > sharellm-$(date +%F).dump
```

Restore into a stopped server:

```bash
docker compose stop sharellm
docker compose exec -T postgres pg_restore -U sharellm -d sharellm --clean --if-exists < sharellm-YYYY-MM-DD.dump
docker compose start sharellm
```

Also keep a copy of `.env` and `secrets/` in your secret store. Caddy's certificates (`caddy_data`
volume) are re-issued automatically if lost.

### Scaling

This Compose setup runs one server instance. Server instances share all state through PostgreSQL,
so you can run several of them against one external database, on several hosts or on Kubernetes.
[scaling.md](scaling.md) explains how the server scales, sizes the database and connection pools,
and includes an example Kubernetes deployment in `deploy/kubernetes/`.

## Security checklist

- [ ] DNS points at the host, and `https://<domain>/health` returns `{"status":"UP"}` with a valid certificate.
- [ ] Only ports 80 and 443 are open in the host firewall. PostgreSQL and port 8081 are not reachable from outside.
- [ ] `secrets/` was generated on the host, is not in git, and is backed up in a secret store.
- [ ] Owner keys are given only to trusted callers; each tenant gets its own key.
- [ ] The worker key is installed only on trusted, consenting laptops. Anyone holding it can read the prompts dispatched to workers.
- [ ] LiteLLM and the proxy timeouts exceed `REQUEST_WAIT_SECONDS`.
- [ ] Rate limiting is configured where needed. Caddy's standard build has no rate limiter; use `QUEUE_LIMIT`, a load balancer or a Caddy build with a rate-limit module.
- [ ] Database backups run on a schedule, and you have tested a restore.
- [ ] Base images are updated regularly (`docker compose build --pull`). For reproducible builds, pin them by digest in the `Dockerfile` and `compose.yaml`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `required variable ... is missing a value` | `.env` misses `SHARELLM_DOMAIN` or `ACME_EMAIL`, or `COMPOSE_FILE` is not set |
| Server exits with `Set at least one owner key ...` | A secret file is missing or empty; run `deploy/generate-secrets.sh` |
| Server exits with `password authentication failed` | `secrets/database_password` changed after the database volume was created; see "Rotating keys" |
| Permission denied on `/run/secrets/...` | Secret files are not readable; `chmod 644 secrets/*` |
| Caddy cannot obtain a certificate | DNS does not point at the host, or port 80/443 is blocked; see `docker compose logs caddy` |
| Callers get `504` with `X-ShareLLM-Request-Id` | No worker answered within `REQUEST_WAIT_SECONDS`; poll the `Location`, and check that a worker with that model is online (`/api/tags`) |
| Callers get `413` | The body exceeds `MAX_BODY_BYTES` (server) or `MAX_REQUEST_BODY` (proxy) |
