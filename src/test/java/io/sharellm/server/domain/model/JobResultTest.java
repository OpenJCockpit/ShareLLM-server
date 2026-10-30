package io.sharellm.server.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobResultTest {
    private static JobResult result(String body, Integer status) {
        return new JobResult(UUID.randomUUID(), "chat", "m", JobState.COMPLETED, body, status);
    }

    @Test
    void isFinishedOnceABodyWasRecorded() {
        assertThat(result(null, null).isFinished()).isFalse();
        assertThat(result("{}", 200).isFinished()).isTrue();
    }

    @Test
    void onlyStatus200IsSuccessful() {
        assertThat(result("{}", 200).isSuccessful()).isTrue();
        assertThat(result("{}", 502).isSuccessful()).isFalse();
        assertThat(result(null, null).isSuccessful()).isFalse();
    }
}
