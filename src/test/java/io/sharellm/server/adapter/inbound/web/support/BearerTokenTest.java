package io.sharellm.server.adapter.inbound.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BearerTokenTest {
    @Test
    void extractsAndTrimsTheToken() {
        assertThat(BearerToken.from("Bearer  secret ")).isEqualTo("secret");
        assertThat(BearerToken.from("bearer secret")).isEqualTo("secret");
    }

    @Test
    void otherSchemesAndMissingHeadersYieldNoToken() {
        assertThat(BearerToken.from(null)).isEmpty();
        assertThat(BearerToken.from("Basic dXNlcjpwdw==")).isEmpty();
        assertThat(BearerToken.from("Bear")).isEmpty();
    }
}
