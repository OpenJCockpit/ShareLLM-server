# Client integrations

ShareLLM Server speaks the Ollama and OpenAI APIs, so most LLM clients work with it unchanged.
Every client authenticates with an **owner key** (one of `SHARELLM_PUBLIC_KEYS`) as bearer token,
and must ask for a model name that a connected worker advertises (see `GET /api/tags`).

## LiteLLM (recommended)

[`litellm-config.example.yaml`](../litellm-config.example.yaml) routes a LiteLLM model through
ShareLLM's OpenAI-compatible endpoint:

```yaml
model_list:
  - model_name: shared-llama
    litellm_params:
      model: openai/llama3.2:latest
      api_base: https://sharellm.example.org/v1
      api_key: os.environ/SHARELLM_PUBLIC_KEY
      timeout: 350
```

Set `SHARELLM_PUBLIC_KEY` to one of the server's owner keys, and point your agents at LiteLLM's
ordinary OpenAI-compatible API with model `shared-llama`.

- **LiteLLM's `timeout` must exceed the expected inference time** and any proxy timeouts. The
  server waits up to `REQUEST_WAIT_SECONDS` (default 300) before answering `504`.
- A model that no online laptop offers stays queued until a matching laptop registers, or until the
  synchronous call times out.

Alternatively, LiteLLM's Ollama provider can use the ShareLLM root URL as its Ollama `api_base`;
ShareLLM exposes `/api/chat` and `/api/generate`. The OpenAI route above is more explicit and easier
to validate.

## Embabel and Spring AI

With Embabel's `embabel-agent-starter-openai-custom` dependency,
[`embabel-application.example.yaml`](../embabel-application.example.yaml) shows the OpenAI-custom
provider configuration:

```yaml
embabel:
  agent:
    platform:
      models:
        openai:
          custom:
            api-key: ${SHARELLM_PUBLIC_KEY}
            base-url: https://sharellm.example.org
            models: llama3.2:latest
  models:
    defaultLlm: llama3.2:latest
```

Model registry and property names can vary by Embabel release, so align this example with your
Embabel version's starter documentation.

## Other OpenAI and Ollama clients

Whether you configure **the root URL** or **the root plus `/v1`** depends on what the client
appends itself:

| Client | Base URL |
|---|---|
| LiteLLM OpenAI provider | `https://sharellm.example.org/v1` |
| Spring AI / Embabel OpenAI-custom (adds `/v1/chat/completions` itself) | `https://sharellm.example.org` |
| Ollama clients | `https://sharellm.example.org` |

## Try it with curl

```bash
curl -sS https://sharellm.example.org/api/tags \
  -H "Authorization: Bearer $SHARELLM_PUBLIC_KEY"

curl -sS https://sharellm.example.org/api/chat \
  -H "Authorization: Bearer $SHARELLM_PUBLIC_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"llama3.2:latest","messages":[{"role":"user","content":"Say hello"}],"stream":false}'
```

The model name must match a name advertised by a worker **exactly** (for example
`llama3.2:latest`). The server never connects to the laptop; Ollama runs only on the laptop.

For long jobs or clients with short HTTP timeouts, use the [asynchronous API](api.md#asynchronous-requests).
