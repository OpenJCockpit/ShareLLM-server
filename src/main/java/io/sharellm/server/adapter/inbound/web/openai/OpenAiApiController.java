package io.sharellm.server.adapter.inbound.web.openai;

import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.json;
import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.of;
import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.timeout;

import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI-compatible adapter on top of exactly the same Ollama job queue. */
@RestController
public class OpenAiApiController {
    private final RequestAuthenticator authenticator;
    private final InferenceUseCase inference;
    private final OpenAiTranslator translator;
    private final ObjectMapper mapper;

    public OpenAiApiController(RequestAuthenticator authenticator, InferenceUseCase inference,
                               OpenAiTranslator translator, ObjectMapper mapper) {
        this.authenticator = authenticator;
        this.inference = inference;
        this.translator = translator;
        this.mapper = mapper;
    }

    @GetMapping({"/v1/models", "/models"})
    public Map<String, Object> models(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.owner(authorization);
        return Map.of("object", "list", "data", inference.availableModels().stream().map(model ->
                Map.of("id", model, "object", "model", "created", 0, "owned_by", "sharellm")).toList());
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<String> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        boolean stream = body.path("stream").asBoolean(false);
        return relay(InferenceEndpoint.OPENAI_CHAT, translator.chatRequest(body), owner, stream,
                (raw, outcome) -> translator.chatResponse(raw, outcome.requestId(), outcome.result().model(), stream));
    }

    @PostMapping("/v1/completions")
    public ResponseEntity<String> completion(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        return relay(InferenceEndpoint.OPENAI_COMPLETION, translator.completionRequest(body), owner,
                body.path("stream").asBoolean(false),
                (raw, outcome) -> translator.completionResponse(raw, outcome.requestId(), outcome.result().model()));
    }

    @PostMapping("/v1/embeddings")
    public ResponseEntity<String> embedding(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody JsonNode body) throws InterruptedException {
        String owner = authenticator.owner(authorization);
        return relay(InferenceEndpoint.OPENAI_EMBEDDING, translator.embeddingRequest(body), owner, false,
                (raw, outcome) -> translator.embeddingResponse(raw, outcome.result().model()));
    }

    /** Queues the translated request, waits, and converts a successful Ollama result. */
    private ResponseEntity<String> relay(InferenceEndpoint endpoint, ObjectNode ollama, String owner, boolean stream,
                                         BiFunction<JsonNode, InferenceOutcome, ObjectNode> convert)
            throws InterruptedException {
        InferenceOutcome outcome = inference.submitAndAwait(
                new InferenceRequest(endpoint, ollama.path("model").asString(""), ollama.toString()), owner);
        UUID id = outcome.requestId();
        JobResult result = outcome.result();
        if (!result.isFinished()) return timeout(id);
        if (!result.isSuccessful()) return json(HttpStatus.BAD_GATEWAY.value(), result.body(), id);
        ObjectNode converted = convert.apply(mapper.readTree(result.body()), outcome);
        // A single delayed server-sent event, not token-by-token streaming.
        if (stream) return of(JobResult.SUCCESS_STATUS, "data: " + converted + "\n\ndata: [DONE]\n\n", id,
                MediaType.TEXT_EVENT_STREAM);
        return json(JobResult.SUCCESS_STATUS, converted.toString(), id);
    }
}
