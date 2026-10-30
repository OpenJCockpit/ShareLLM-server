package io.sharellm.server.adapter.inbound.web.requests;

import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.REQUEST_ID_HEADER;
import static io.sharellm.server.adapter.inbound.web.support.InferenceResponses.location;

import io.sharellm.server.adapter.inbound.web.support.JsonBodies;
import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceRequest;
import io.sharellm.server.domain.model.JobResult;
import io.sharellm.server.domain.model.JobState;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** ShareLLM extension for callers that cannot hold a long HTTP request open. */
@RestController
public class AsyncRequestController {
    private final RequestAuthenticator authenticator;
    private final InferenceUseCase inference;
    private final ObjectMapper mapper;

    public AsyncRequestController(RequestAuthenticator authenticator, InferenceUseCase inference, ObjectMapper mapper) {
        this.authenticator = authenticator;
        this.inference = inference;
        this.mapper = mapper;
    }

    @PostMapping("/sharellm/requests")
    public ResponseEntity<Map<String, String>> enqueue(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode envelope) {
        String owner = authenticator.owner(authorization);
        InferenceEndpoint endpoint = InferenceEndpoint.fromWireName(envelope.path("endpoint").asString("chat"))
                .filter(InferenceEndpoint::isOllamaNative)
                .orElseThrow(() -> new InvalidRequestException("Unsupported endpoint"));
        JsonNode request = JsonBodies.requireObject(envelope.get("request"));
        UUID id = inference.enqueue(new InferenceRequest(endpoint, JsonBodies.model(request), request.toString()), owner);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, location(id))
                .header(REQUEST_ID_HEADER, id.toString())
                .body(Map.of("id", id.toString(), "status", JobState.READY.name(), "resultUrl", location(id)));
    }

    /** 202 while queued or leased, 200 when completed, 502 after a terminal failure. */
    @GetMapping("/sharellm/requests/{id}")
    public ResponseEntity<Map<String, Object>> result(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        JobResult result = inference.findResult(id, authenticator.owner(authorization));
        if (!result.isFinished())
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id, "status", result.state()));
        return ResponseEntity.status(result.isSuccessful() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                .body(Map.of("id", id.toString(), "status", result.state(), "endpoint", result.endpoint(),
                        "model", result.model(), "result", mapper.readTree(result.body())));
    }
}
