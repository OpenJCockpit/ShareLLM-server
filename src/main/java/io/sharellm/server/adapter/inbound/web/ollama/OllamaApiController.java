package io.sharellm.server.adapter.inbound.web.ollama;

import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.json;
import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.of;
import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.timeout;

import io.sharellm.server.adapter.inbound.web.support.JsonBodies;
import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The subset of the Ollama API that maps onto queued inference. */
@RestController
public class OllamaApiController {
    static final String VERSION = "0.1.0";

    private final RequestAuthenticator authenticator;
    private final InferenceUseCase inference;

    public OllamaApiController(RequestAuthenticator authenticator, InferenceUseCase inference) {
        this.authenticator = authenticator;
        this.inference = inference;
    }

    @PostMapping("/api/chat")
    public ResponseEntity<String> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        if (!body.path("messages").isArray()) throw new InvalidRequestException("messages array required");
        return infer(InferenceEndpoint.CHAT, body, owner);
    }

    @PostMapping("/api/generate")
    public ResponseEntity<String> generate(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody JsonNode body) throws InterruptedException {
        return infer(InferenceEndpoint.GENERATE, body, authenticator.owner(authorization));
    }

    @PostMapping("/api/embed")
    public ResponseEntity<String> embed(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        if (body.get("input") == null) throw new InvalidRequestException("input required");
        return infer(InferenceEndpoint.EMBED, body, owner);
    }

    @PostMapping("/api/embeddings")
    public ResponseEntity<String> embeddings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        if (body.get("prompt") == null) throw new InvalidRequestException("prompt required");
        return infer(InferenceEndpoint.EMBEDDINGS, body, owner);
    }

    @PostMapping("/api/show")
    public ResponseEntity<String> show(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody JsonNode body) throws InterruptedException {
        return infer(InferenceEndpoint.SHOW, body, authenticator.owner(authorization));
    }

    /** Model discovery from live workers; size, digest and details are synthetic. */
    @GetMapping("/api/tags")
    public Map<String, Object> tags(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.owner(authorization);
        String now = Instant.now().toString();
        List<Map<String, Object>> models = inference.availableModels().stream().map(model -> Map.<String, Object>of(
                "name", model, "model", model, "modified_at", now, "size", 0, "digest", "", "details", Map.of()))
                .toList();
        return Map.of("models", models);
    }

    /** The ShareLLM version, not the version of the Ollama binaries on the workers. */
    @GetMapping("/api/version")
    public Map<String, String> version(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.owner(authorization);
        return Map.of("version", VERSION);
    }

    private ResponseEntity<String> infer(InferenceEndpoint endpoint, JsonNode body, String owner)
            throws InterruptedException {
        JsonBodies.requireObject(body);
        boolean stream = endpoint.isStreamable() && body.path("stream").asBoolean(true);
        InferenceOutcome outcome = inference.submitAndAwait(
                new InferenceRequest(endpoint, JsonBodies.model(body), body.toString()), owner);
        JobResult result = outcome.result();
        if (!result.isFinished()) return timeout(outcome.requestId());
        if (!result.isSuccessful()) return json(result.httpStatus(), result.body(), outcome.requestId());
        // Valid NDJSON: one delayed, complete event rather than token-by-token streaming.
        if (stream) return of(JobResult.SUCCESS_STATUS, result.body() + "\n", outcome.requestId(), MediaType.APPLICATION_NDJSON);
        return json(JobResult.SUCCESS_STATUS, result.body(), outcome.requestId());
    }
}
