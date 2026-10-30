package io.sharellm.server.adapter.inbound.web.support;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Builds inference responses that always tell the caller which request ID they belong to. */
public final class InferenceResponses {
    public static final String REQUEST_ID_HEADER = "X-ShareLLM-Request-Id";
    public static final String REQUESTS_PATH = "/sharellm/requests/";

    private InferenceResponses() {}

    public static String location(UUID id) { return REQUESTS_PATH + id; }

    public static ResponseEntity<String> json(int status, String body, UUID id) {
        return of(status, body, id, MediaType.APPLICATION_JSON);
    }

    public static ResponseEntity<String> of(int status, String body, UUID id, MediaType mediaType) {
        return ResponseEntity.status(status).header(REQUEST_ID_HEADER, id.toString()).contentType(mediaType).body(body);
    }

    /** The synchronous wait ended before a worker answered; the caller can poll {@link #location(UUID)}. */
    public static ResponseEntity<String> timeout(UUID id) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("error", "Result is not ready; poll the request URL with the same owner key");
        data.put("request_id", id.toString());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .header(REQUEST_ID_HEADER, id.toString())
                .header(HttpHeaders.LOCATION, location(id))
                .contentType(MediaType.APPLICATION_JSON).body(data.toString());
    }
}
