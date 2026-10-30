package io.sharellm.server.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sharellm.server.application.settings.AccessSettings;
import io.sharellm.server.application.settings.QueueSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShareLlmPropertiesTest {
    private static ShareLlmProperties properties(List<String> publicKeys, String workerKey) {
        return new ShareLlmProperties(publicKeys, workerKey, 35, 45, 3, 300, 15, 350, 24, 10, 2048);
    }

    @Test
    void splitsIntoAccessAndQueueSettings() {
        ShareLlmProperties properties = properties(List.of(" a ", ""), "w\n");
        assertThat(properties.accessSettings()).isEqualTo(new AccessSettings(List.of("a"), "w"));
        assertThat(properties.queueSettings()).isEqualTo(new QueueSettings(35, 45, 3, 300, 15, 350, 24, 10, 2048));
    }

    @Test
    void requiresAnOwnerKey() {
        assertThatThrownBy(() -> properties(null, "w")).hasMessageContaining("SHARELLM_PUBLIC_KEYS");
        assertThatThrownBy(() -> properties(List.of(" "), "w")).hasMessageContaining("SHARELLM_PUBLIC_KEYS");
    }

    @Test
    void requiresAWorkerKey() {
        assertThatThrownBy(() -> properties(List.of("a"), null)).hasMessageContaining("SHARELLM_WORKER_KEY");
        assertThatThrownBy(() -> properties(List.of("a"), " ")).hasMessageContaining("SHARELLM_WORKER_KEY");
    }

    @Test
    void workerKeyMustNotBeAnOwnerKey() {
        assertThatThrownBy(() -> properties(List.of("a", "shared"), "shared"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must differ");
    }
}
