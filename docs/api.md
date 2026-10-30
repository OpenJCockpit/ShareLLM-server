# API reference

ShareLLM Server is an inference gateway with Ollama- and OpenAI-compatible endpoints. The
implemented contract is documented in [`openapi.yaml`](../openapi.yaml), which is also served by the
server at `/openapi.yaml`.

All endpoints except `/health` and `/openapi.yaml` need a bearer token: an **owner key** for the inference APIs, the
**worker key** for `/internal/*`.

## Ollama endpoints

Implemented from the [Ollama OpenAPI specification](https://github.com/ollama/ollama/blob/main/docs/openapi.yaml):

| Endpoint | Behavior |
|---|---|
| `POST /api/chat` | Ollama ChatRequest → ChatResponse |
| `POST /api/generate` | Ollama GenerateRequest → GenerateResponse |
| `POST /api/embed` | Ollama embeddings (plural `embeddings`) |
| `POST /api/embeddings` | Legacy embedding API |
| `POST /api/show` | Model information, answered by a worker that has the model |
| `GET /api/tags` | Models advertised by live workers, with synthetic metadata |
| `GET /api/version` | ShareLLM version string, not an Ollama binary version |

## OpenAI-compatible endpoints

| Endpoint | Behavior |
|---|---|
| `POST /v1/chat/completions` | Chat completions |
| `POST /v1/completions` | Text completions |
| `POST /v1/embeddings` | Embeddings |
| `GET /v1/models` (alias `GET /models`) | Models advertised by live workers |

## Asynchronous requests

Useful behind short HTTP timeouts, or for long jobs:

```bash
curl -i https://sharellm.example.org/sharellm/requests \
  -H "Authorization: Bearer $SHARELLM_PUBLIC_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"endpoint":"chat","request":{"model":"llama3.2:latest","messages":[{"role":"user","content":"Hello"}]}}'
# HTTP 202, Location: /sharellm/requests/<uuid>

curl -sS https://sharellm.example.org/sharellm/requests/<uuid> \
  -H "Authorization: Bearer $SHARELLM_PUBLIC_KEY"
# 202 = queued/leased, 200 = completed, 502 = terminal failure, 404 = wrong owner or unknown ID
```

- Synchronous Ollama and OpenAI requests block for up to `REQUEST_WAIT_SECONDS`. A `504` response
  includes `X-ShareLLM-Request-Id` and `Location`, so the caller can fetch the result later.
- `GET /sharellm/requests/{id}` returns a ShareLLM envelope with the worker's Ollama JSON under
  `result`, also for requests made through `/v1/*`; it does not return an OpenAI-formatted
  completion.
- Results can only be read with the owner key that submitted the request.

## Worker endpoints

Used by the ShareLLM laptop client, authenticated with the worker key:

| Endpoint | Purpose |
|---|---|
| `POST /internal/workers/register` | Register a worker and the models it offers |
| `POST /internal/workers/heartbeat` | Keep the worker alive and extend its lease |
| `GET /internal/jobs/claim?clientId=…&waitSeconds=…` | Long-poll for a job; returns the job and a lease token |
| `POST /internal/jobs/{id}/complete` | Deliver a result for the current lease |
| `POST /internal/jobs/{id}/fail` | Report a failure for the current lease |

A completion or failure with a stale lease token is rejected with `409`. See
[architecture.md](architecture.md) for how leasing works.

## Health

| Endpoint | Purpose |
|---|---|
| `GET /health` | Simple liveness check on the API port |
| `GET :8081/actuator/health/liveness` | Liveness probe on the management port |
| `GET :8081/actuator/health/readiness` | Readiness probe; includes the database |

## Compatibility limits

ShareLLM is an inference gateway, **not a full Ollama server**:

- Ollama model management (pull, push, create, delete, copy), blob operations, `/api/ps` and other
  upstream paths are not implemented.
- Model metadata (`size`, `digest`, `modified_at`, `details`) is synthetic.
- **No token-by-token streaming.** Ollama defaults `stream` to `true`; ShareLLM accepts it but waits
  for the complete worker result and then emits a single final NDJSON event. OpenAI `stream: true`
  likewise emits one final SSE chunk followed by `[DONE]`.
- Some less common Ollama and OpenAI parameters are not mapped by the OpenAI adapter.

This suits LiteLLM and agent inference, but not applications that need token-level streaming,
exact Ollama management behavior or conformance with every path of the upstream specification.
