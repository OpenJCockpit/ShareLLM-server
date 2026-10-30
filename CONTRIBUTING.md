# Contributing to ShareLLM Server

Thanks for your interest in improving ShareLLM Server, the durable request broker that lets
LiteLLM, Embabel and other Ollama- or OpenAI-compatible callers run inference on
outbound-only laptop workers.

This project is published under the Apache License 2.0 and welcomes high-quality bug reports,
fixes, documentation improvements, and examples.

## Ways to contribute

- Report bugs or unexpected behavior (for example lost or duplicated jobs, lease recovery
  problems, or Ollama/OpenAI compatibility gaps)
- Suggest enhancements, such as items from the roadmap in the [README](README.md#roadmap)
- Improve documentation and integration examples (LiteLLM, Embabel, direct clients)
- Submit code changes and tests

## Before you start

- Check whether an issue already exists for the topic you want to work on.
- Keep changes focused and small enough to review easily.
- Prefer clear commit messages and tests for behavioral changes.
- For larger changes (new endpoints, schema changes, changes to leasing or fencing semantics),
  open an issue first to discuss the approach.

## Development setup

Requirements:

- JDK 25
- Maven 3.9+
- Docker with Docker Compose, for the local PostgreSQL 17 database
- Python 3, for the smoke and lease-recovery test scripts
- Optionally, the ShareLLM laptop client and a local Ollama installation for end-to-end testing

Common commands:

```bash
cp .env.example .env          # once: development overlay and dev-only keys

# Start the local PostgreSQL database only
docker compose up -d postgres

# Build and run all tests with the 100% line-coverage gate (Docker must be running for Testcontainers)
mvn -B verify

# Run the server from source
SHARELLM_PUBLIC_KEYS=dev-owner-key SHARELLM_WORKER_KEY=dev-worker-key mvn spring-boot:run

# Or build and run server and database together in Docker
docker compose up --build -d

# With the server and PostgreSQL running: no-Ollama smoke test
SHARELLM_URL=http://localhost:8080 \
SHARELLM_PUBLIC_KEY=dev-owner-key \
SHARELLM_WORKER_KEY=dev-worker-key \
python3 scripts-smoke-test.py

# Simulate a worker disconnect and verify lease recovery and fencing
SHARELLM_URL=http://localhost:8080 SHARELLM_PUBLIC_KEY=dev-owner-key python3 scripts-lease-recovery-test.py
```

See [docs/development.md](docs/development.md) for the development setup,
[docs/deployment.md](docs/deployment.md) for production and [docs/scaling.md](docs/scaling.md) for
scaling and Kubernetes. Changes to `Dockerfile`, the compose files or `deploy/` should keep these
guides accurate.

## Coding guidelines

- Prefer clear, idiomatic Java that matches the surrounding code.
- Respect the layering described in [docs/architecture.md](docs/architecture.md#code-structure): `domain` and `application` stay
  free of Spring, Jackson and JDBC; HTTP and SQL concerns live in the adapters. `ArchitectureTest`
  fails the build when a dependency points the wrong way.
- Keep line coverage at 100%: `mvn -B verify` fails otherwise. Cover new behavior with a unit test,
  a controller slice test (`@WebMvcTest`) or, for SQL, a test that extends `PostgresIntegrationTest`.
- Keep public APIs and REST contracts minimal and documented. When you change an endpoint,
  update `openapi.yaml` and keep `src/main/resources/static/openapi.yaml` identical to it.
- Database changes go in `src/main/resources/schema.sql`; the SQL targets PostgreSQL and
  existing deployments should keep working after an upgrade.
- Preserve the queue guarantees: jobs and results stay durable in PostgreSQL, claims stay atomic,
  writes with a stale lease token are rejected, and processing is at-least-once. Changes that
  touch claiming, heartbeats or lease expiry should come with a test or an updated test script.
- Keep the server safe to run as several instances on the same database; do not make an
  in-process queue or cache the source of truth.
- Add or update tests for logic changes and bug fixes.
- Never commit secrets, owner or worker keys, database passwords or real prompts in code,
  configuration or examples. Use placeholders such as those in `.env.example`.
- Avoid introducing unrelated refactors in the same change.

## Pull requests

1. Fork or branch from the repository's default branch.
2. Make your change with clear, focused commits.
3. Run `mvn -B verify`, and the smoke test scripts when your change affects the queue, the
   worker routes or the request/result API.
4. Open a pull request with:
   - a concise title
   - a summary of the change
   - related issue references when applicable
   - validation steps performed

## Code of conduct

Please follow our [Code of Conduct](CODE_OF_CONDUCT.md). We aim to maintain a respectful,
welcoming, and productive contributor community.

## License

By contributing, you agree that your contributions will be licensed under the project's
[LICENSE](LICENSE).