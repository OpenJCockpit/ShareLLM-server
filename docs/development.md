# Local development

This guide covers running ShareLLM Server on your own machine. For servers reachable by other
people or by remote laptops, follow the [production deployment guide](deployment.md) instead.

## Prerequisites

- Docker with Docker Compose v2
- JDK 25 and Maven 3.9+, to build and test from source
- Python 3, for the smoke and lease-recovery test scripts
- Optionally the ShareLLM laptop client and Ollama, for real inference

## Compose files

| File | Purpose |
|---|---|
| `compose.yaml` | Shared definition of the `postgres` and `sharellm` services. Never used on its own. |
| `compose.dev.yaml` | Development overlay: secrets from `.env`, every port published on `127.0.0.1` only. |
| `compose.prod.yaml` | Production overlay; see [deployment.md](deployment.md). |

The overlay is chosen through `COMPOSE_FILE` in `.env`, so plain `docker compose` commands work.

## First start

```bash
cp .env.example .env          # sets COMPOSE_FILE=compose.yaml:compose.dev.yaml and dev-only keys
docker compose up --build -d
docker compose ps             # both services become "healthy"
```

| URL | What |
|---|---|
| `http://localhost:8080` | API (Ollama, OpenAI, async requests, worker routes) |
| `http://localhost:8080/health` | Simple liveness check |
| `http://localhost:8081/actuator/health/readiness` | Readiness probe, includes the database |
| `localhost:5432` | PostgreSQL (`sharellm` / `sharellm`) |

If one of these ports is taken, change `SHARELLM_HTTP_PORT`, `SHARELLM_MANAGEMENT_HOST_PORT` or
`POSTGRES_HOST_PORT` in `.env`.

Try it:

```bash
curl -s http://localhost:8080/api/tags -H 'Authorization: Bearer dev-owner-key'
```

Rebuild after code changes with `docker compose up --build -d sharellm`, and follow the logs with
`docker compose logs -f sharellm`.

## Running the server from source

Start only the database in Docker and run the server on your machine, for fast restarts and
debugging from your IDE:

```bash
docker compose up -d postgres

export SHARELLM_PUBLIC_KEYS=dev-owner-key
export SHARELLM_WORKER_KEY=dev-worker-key
mvn spring-boot:run
```

The database defaults in `application.yml` match the development database. The server refuses to
start without both keys, and when the worker key equals an owner key.

## Connecting a laptop worker

Start the ShareLLM laptop client with `SHARELLM_SERVER_URL=http://localhost:8080`,
`SHARELLM_WORKER_KEY=dev-worker-key` and `OLLAMA_URL=http://localhost:11434`. Plain HTTP is only
acceptable on your own machine; remote laptops must use a production deployment with HTTPS.

## Tests

```bash
mvn -B verify                 # all tests, 100% line-coverage gate, architecture rules (needs Docker)

# Against a running server, no Ollama needed:
SHARELLM_URL=http://localhost:8080 SHARELLM_PUBLIC_KEY=dev-owner-key \
SHARELLM_WORKER_KEY=dev-worker-key python3 scripts-smoke-test.py
SHARELLM_URL=http://localhost:8080 SHARELLM_PUBLIC_KEY=dev-owner-key \
python3 scripts-lease-recovery-test.py
```

`mvn -B verify` enforces 100% line coverage with JaCoCo; the report is in
`target/site/jacoco/index.html`. The persistence and end-to-end tests start PostgreSQL 17 with
Testcontainers.

The smoke script checks asynchronous submission, the exclusive claim, result delivery and that a
duplicate completion is rejected. The lease-recovery script simulates a lost worker by ageing its
heartbeat, then checks that another worker can claim the job and that the old lease token is
rejected.

The lease-recovery script edits the database through `docker compose exec postgres`, so run it from
the project directory while the development stack is up.

## Resetting

```bash
docker compose down           # stop, keep the data
docker compose down -v        # stop and delete the database volume
```

`DATABASE_PASSWORD` only takes effect when the database volume is first created; remove the volume
after changing it.
