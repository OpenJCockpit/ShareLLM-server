package io.sharellm.server.domain.model;

/** An Ollama-format request body to run on a worker that advertises {@code model}. */
public record InferenceRequest(InferenceEndpoint endpoint, String model, String payloadJson) {}
