package io.sharellm.server.adapter.inbound.web.openai;

import io.sharellm.server.adapter.inbound.web.support.JsonBodies;
import io.sharellm.server.domain.exception.InvalidRequestException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts OpenAI request bodies to Ollama requests, and Ollama results back to OpenAI responses. */
@Component
public class OpenAiTranslator {
    private static final String[] SAMPLING_OPTIONS =
            {"temperature", "top_p", "top_k", "seed", "frequency_penalty", "presence_penalty"};

    private final ObjectMapper mapper;

    public OpenAiTranslator(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode chatRequest(JsonNode body) {
        if (!body.path("messages").isArray()) throw new InvalidRequestException("messages array required");
        ObjectNode ollama = baseRequest(body);
        ArrayNode messages = ollama.putArray("messages");
        for (JsonNode message : body.path("messages")) {
            if (!message.isObject()) throw new InvalidRequestException("Invalid message");
            ObjectNode value = (ObjectNode) message.deepCopy();
            if ("developer".equals(value.path("role").asString(""))) value.put("role", "system");
            messages.add(value);
        }
        copy(body, ollama, "tools", "tools");
        copy(body, ollama, "think", "think");
        return ollama;
    }

    public ObjectNode completionRequest(JsonNode body) {
        if (!body.path("prompt").isString()) throw new InvalidRequestException("Only string prompt supported");
        ObjectNode ollama = baseRequest(body);
        ollama.set("prompt", body.path("prompt"));
        return ollama;
    }

    public ObjectNode embeddingRequest(JsonNode body) {
        if (body.get("input") == null) throw new InvalidRequestException("input required");
        ObjectNode ollama = baseRequest(body);
        ollama.set("input", body.get("input"));
        return ollama;
    }

    /** A chat completion, or the single chunk of a delayed stream when {@code stream} is set. */
    public ObjectNode chatResponse(JsonNode raw, UUID id, String model, boolean stream) {
        ObjectNode output = envelope("chatcmpl-" + id, stream ? "chat.completion.chunk" : "chat.completion", model);
        ObjectNode choice = output.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = assistantMessage(raw.path("message"));
        if (stream) indexToolCalls(message);
        choice.set(stream ? "delta" : "message", message);
        choice.put("finish_reason", message.has("tool_calls") ? "tool_calls" : finishReason(raw));
        output.set("usage", usage(raw));
        return output;
    }

    public ObjectNode completionResponse(JsonNode raw, UUID id, String model) {
        ObjectNode output = envelope("cmpl-" + id, "text_completion", model);
        ObjectNode choice = output.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("text", raw.path("response").asString(""));
        choice.put("finish_reason", finishReason(raw));
        output.set("usage", usage(raw));
        return output;
    }

    public ObjectNode embeddingResponse(JsonNode raw, String model) {
        ObjectNode response = mapper.createObjectNode();
        response.put("object", "list");
        response.put("model", model);
        ArrayNode data = response.putArray("data");
        JsonNode embeddings = raw.path("embeddings");
        for (int i = 0; embeddings.isArray() && i < embeddings.size(); i++) {
            ObjectNode entry = data.addObject();
            entry.put("object", "embedding");
            entry.put("index", i);
            entry.set("embedding", embeddings.get(i));
        }
        int tokens = raw.path("prompt_eval_count").asInt(0);
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", tokens);
        usage.put("total_tokens", tokens);
        return response;
    }

    /** Each public request method checks a field that only an object body can have before calling this. */
    private ObjectNode baseRequest(JsonNode body) {
        ObjectNode target = mapper.createObjectNode();
        target.put("model", JsonBodies.model(body));
        target.put("stream", false);
        ObjectNode options = target.putObject("options");
        for (String option : SAMPLING_OPTIONS) copy(body, options, option, option);
        copy(body, options, "max_tokens", "num_predict");
        copy(body, options, "max_completion_tokens", "num_predict");
        JsonNode format = body.path("response_format");
        String formatType = format.path("type").asString("");
        if ("json_object".equals(formatType)) target.put("format", "json");
        if ("json_schema".equals(formatType) && format.path("json_schema").has("schema"))
            target.set("format", format.path("json_schema").path("schema"));
        return target;
    }

    private ObjectNode envelope(String id, String object, String model) {
        ObjectNode output = mapper.createObjectNode();
        output.put("id", id);
        output.put("object", object);
        output.put("created", Instant.now().getEpochSecond());
        output.put("model", model);
        return output;
    }

    private ObjectNode assistantMessage(JsonNode rawMessage) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", rawMessage.path("content").asString(""));
        JsonNode rawCalls = rawMessage.path("tool_calls");
        if (rawCalls.isArray() && !rawCalls.isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (JsonNode call : rawCalls) calls.add(toolCall(call));
        }
        return message;
    }

    private ObjectNode toolCall(JsonNode call) {
        ObjectNode normalized = mapper.createObjectNode();
        String callId = call.path("id").asString("");
        normalized.put("id", callId.isBlank() ? "call_" + UUID.randomUUID().toString().replace("-", "") : callId);
        normalized.put("type", "function");
        ObjectNode function = normalized.putObject("function");
        JsonNode source = call.path("function");
        function.put("name", source.path("name").asString(""));
        JsonNode arguments = source.path("arguments");
        function.put("arguments", arguments.isString() ? arguments.asString("")
                : arguments.isMissingNode() ? "{}" : arguments.toString());
        return normalized;
    }

    /** Streaming deltas identify each tool call by position. */
    private static void indexToolCalls(ObjectNode message) {
        int index = 0;
        for (JsonNode call : message.path("tool_calls")) ((ObjectNode) call).put("index", index++);
    }

    private ObjectNode usage(JsonNode raw) {
        ObjectNode output = mapper.createObjectNode();
        int input = raw.path("prompt_eval_count").asInt(0);
        int generated = raw.path("eval_count").asInt(0);
        output.put("prompt_tokens", input);
        output.put("completion_tokens", generated);
        output.put("total_tokens", input + generated);
        return output;
    }

    private static String finishReason(JsonNode raw) {
        return "length".equals(raw.path("done_reason").asString("")) ? "length" : "stop";
    }

    private static void copy(JsonNode from, ObjectNode to, String source, String target) {
        if (from.get(source) != null) to.set(target, from.get(source));
    }
}
