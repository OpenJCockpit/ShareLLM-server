package io.sharellm.server.domain.model;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of inference work a worker can perform, with their stable wire names. */
public enum InferenceEndpoint {
    CHAT("chat", true, true),
    GENERATE("generate", true, true),
    EMBED("embed", true, false),
    EMBEDDINGS("embeddings", true, false),
    SHOW("show", true, false),
    OPENAI_CHAT("openai-chat", false, false),
    OPENAI_COMPLETION("openai-completion", false, false),
    OPENAI_EMBEDDING("openai-embedding", false, false);

    private final String wireName;
    private final boolean ollamaNative;
    private final boolean streamable;

    InferenceEndpoint(String wireName, boolean ollamaNative, boolean streamable) {
        this.wireName = wireName;
        this.ollamaNative = ollamaNative;
        this.streamable = streamable;
    }

    public String wireName() { return wireName; }

    /** Whether callers may address this endpoint directly with an Ollama request body. */
    public boolean isOllamaNative() { return ollamaNative; }

    /** Whether Ollama clients expect an NDJSON stream from this endpoint. */
    public boolean isStreamable() { return streamable; }

    public static Optional<InferenceEndpoint> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(endpoint -> endpoint.wireName.equals(wireName)).findFirst();
    }
}
