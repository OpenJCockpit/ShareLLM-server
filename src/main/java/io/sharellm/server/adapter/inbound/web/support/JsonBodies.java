package io.sharellm.server.adapter.inbound.web.support;

import io.sharellm.server.domain.exception.InvalidRequestException;
import tools.jackson.databind.JsonNode;

/** Shape checks shared by the JSON request bodies of all inference APIs. */
public final class JsonBodies {
    private JsonBodies() {}

    public static JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) throw new InvalidRequestException("JSON object required");
        return body;
    }

    public static String model(JsonNode body) { return body.path("model").asString(""); }
}
