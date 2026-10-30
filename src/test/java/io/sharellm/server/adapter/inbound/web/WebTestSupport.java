package io.sharellm.server.adapter.inbound.web;

import io.sharellm.server.domain.model.InferenceOutcome;
import io.sharellm.server.domain.model.JobResult;
import io.sharellm.server.domain.model.JobState;
import java.util.UUID;

/** Shared fixtures for the controller slice tests. */
public final class WebTestSupport {
    public static final String OWNER_HEADER = "Bearer owner-key";
    public static final String OWNER = "owner-hash";
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WebTestSupport() {}

    public static InferenceOutcome pending(String endpoint, String model) {
        return new InferenceOutcome(ID, new JobResult(ID, endpoint, model, JobState.LEASED, null, null));
    }

    public static InferenceOutcome completed(String endpoint, String model, String body) {
        return new InferenceOutcome(ID, new JobResult(ID, endpoint, model, JobState.COMPLETED, body, 200));
    }

    public static InferenceOutcome failed(String endpoint, String model) {
        return new InferenceOutcome(ID, new JobResult(ID, endpoint, model, JobState.FAILED,
                "{\"error\":\"Worker lost; retry budget exhausted\"}", 502));
    }
}
