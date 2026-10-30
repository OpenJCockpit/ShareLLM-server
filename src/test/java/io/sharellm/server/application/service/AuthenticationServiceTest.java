package io.sharellm.server.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sharellm.server.application.settings.AccessSettings;
import io.sharellm.server.domain.exception.AuthenticationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticationServiceTest {
    private final AuthenticationService service =
            new AuthenticationService(new AccessSettings(List.of("alpha", "beta"), "worker-secret"));

    @Test
    void ownerIdentityIsAStableHashPerKey() {
        String alpha = service.authenticateOwner("alpha");
        assertThat(alpha).hasSize(64).isEqualTo(service.authenticateOwner("alpha")).isNotEqualTo("alpha");
        assertThat(service.authenticateOwner("beta")).isNotEqualTo(alpha);
    }

    @Test
    void rejectsUnknownMissingAndBlankOwnerTokens() {
        assertThatThrownBy(() -> service.authenticateOwner("wrong"))
                .isInstanceOf(AuthenticationException.class).hasMessage("Invalid owner token");
        assertThatThrownBy(() -> service.authenticateOwner(null)).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> service.authenticateOwner("")).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void ownerKeysAreNotWorkerKeys() {
        assertThatThrownBy(() -> service.authenticateOwner("worker-secret")).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> service.authenticateWorker("alpha"))
                .isInstanceOf(AuthenticationException.class).hasMessage("Invalid worker token");
    }

    @Test
    void acceptsOnlyTheWorkerKey() {
        assertThatCode(() -> service.authenticateWorker("worker-secret")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.authenticateWorker(null)).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void blankWorkerKeyNeverMatches() {
        AuthenticationService unconfigured = new AuthenticationService(new AccessSettings(List.of(), ""));
        assertThatThrownBy(() -> unconfigured.authenticateWorker("")).isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> unconfigured.authenticateOwner("")).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void unavailableDigestIsAnIllegalState() {
        assertThatThrownBy(() -> AuthenticationService.digest("text", "NO-SUCH-ALGORITHM"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NO-SUCH-ALGORITHM");
    }
}
