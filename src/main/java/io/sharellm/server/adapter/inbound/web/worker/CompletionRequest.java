package io.sharellm.server.adapter.inbound.web.worker;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The worker's Ollama response for a job it holds under {@code leaseToken}. */
public record CompletionRequest(String clientId, UUID leaseToken, JsonNode response) {}
