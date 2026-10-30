# Security policy

We take security seriously and appreciate responsible disclosure of vulnerabilities.

## Reporting a vulnerability

Please do not open a public GitHub issue for security vulnerabilities.

Instead, send a private report through GitHub's private vulnerability reporting:

- https://github.com/OpenJCockpit/ShareLLM-server/security/advisories/new

If that is not possible, email MetaFactory B.V. at info@metafactory.nl.

Include:

- a description of the issue
- affected version or commit
- affected component (for example owner-key or worker-key authentication, the Ollama
  `/api/*` or OpenAI-compatible `/v1/*` endpoints, the async `/sharellm/requests` API, the
  `/internal` worker routes, job leasing and lease-token fencing, or the database schema)
- reproduction steps or proof of concept
- impact assessment
- any suggested mitigation

Examples of issues we want to hear about include reading or completing another owner's
requests, bypassing authentication, accepting results with a stale or forged lease token,
SQL injection, and request handling that exhausts the server or database.

## Response expectations

We will make reasonable efforts to:

- acknowledge receipt within 5 business days
- assess the issue promptly
- coordinate a fix and disclosure schedule

## Safe handling

Please avoid exposing sensitive details in public discussions until we have had a chance to
evaluate and fix the issue.

Never include real owner keys, worker keys, database credentials, prompts or customer data in a
report. If a proof of concept needs a secret, use a revoked or throwaway one.

## Scope

This policy applies to the ShareLLM Server project and its repository content. It does not cover
downstream usage, the ShareLLM laptop client, Ollama, LiteLLM, Embabel, or other third-party
systems integrated by users.

The following are known, documented limitations and are not considered vulnerabilities in
themselves:

- The local development defaults shipped with this repository, such as the PostgreSQL
  credentials and published ports in `compose.dev.yaml` and the dev-only keys in `.env.example`.
  Using them outside local development is outside the scope of this policy; production deployments
  are expected to follow `docs/deployment.md`, which uses generated secrets.
- The worker key is a shared deployment secret, not a per-worker identity: any holder can act as
  any worker and can read the prompts dispatched to it. Callers that share an owner key share
  access to each other's results.
- The server does not terminate TLS or apply rate limiting itself; this is expected to be handled
  by the ingress or reverse proxy (the production setup terminates TLS with Caddy).
- Processing is at-least-once, so a prompt may run more than once when a worker disconnects.

See [docs/deployment.md](docs/deployment.md) and [docs/configuration.md](docs/configuration.md#security-notes)
for the recommended deployment hardening.
