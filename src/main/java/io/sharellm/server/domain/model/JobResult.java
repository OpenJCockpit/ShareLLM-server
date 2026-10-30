package io.sharellm.server.domain.model;

import java.util.UUID;

/** Current state of a job as seen by its owner; {@code body} is present once a result was recorded. */
public record JobResult(UUID id, String endpoint, String model, JobState state, String body, Integer httpStatus) {
    public static final int SUCCESS_STATUS = 200;

    public boolean isFinished() { return body != null; }

    public boolean isSuccessful() { return httpStatus != null && httpStatus == SUCCESS_STATUS; }
}
