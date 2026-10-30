package io.sharellm.server.adapter.inbound.web.worker;

import java.util.List;

public record WorkerRegistrationRequest(String clientId, List<String> models) {}
