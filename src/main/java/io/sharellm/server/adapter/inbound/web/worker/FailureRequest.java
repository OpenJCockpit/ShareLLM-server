package io.sharellm.server.adapter.inbound.web.worker;

import java.util.UUID;

public record FailureRequest(String clientId, UUID leaseToken, String error) {}
