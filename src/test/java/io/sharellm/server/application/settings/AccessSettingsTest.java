package io.sharellm.server.application.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccessSettingsTest {
    @Test
    void trimsOwnerKeysAndDropsBlanks() {
        AccessSettings settings = new AccessSettings(List.of(" alpha ", "", "  ", "beta"), "worker");
        assertThat(settings.ownerKeys()).containsExactly("alpha", "beta");
        assertThat(settings.workerKey()).isEqualTo("worker");
    }

    @Test
    void missingValuesBecomeEmpty() {
        AccessSettings settings = new AccessSettings(null, null);
        assertThat(settings.ownerKeys()).isEmpty();
        assertThat(settings.workerKey()).isEmpty();
    }
}
