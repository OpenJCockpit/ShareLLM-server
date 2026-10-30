package io.sharellm.server.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InferenceEndpointTest {
    @ParameterizedTest
    @EnumSource(InferenceEndpoint.class)
    void wireNamesRoundTrip(InferenceEndpoint endpoint) {
        assertThat(InferenceEndpoint.fromWireName(endpoint.wireName())).contains(endpoint);
    }

    @Test
    void unknownWireNameIsEmpty() {
        assertThat(InferenceEndpoint.fromWireName("pull")).isEmpty();
        assertThat(InferenceEndpoint.fromWireName(null)).isEmpty();
    }

    @Test
    void onlyOllamaChatAndGenerateStream() {
        assertThat(InferenceEndpoint.CHAT.isStreamable()).isTrue();
        assertThat(InferenceEndpoint.GENERATE.isStreamable()).isTrue();
        assertThat(InferenceEndpoint.EMBED.isStreamable()).isFalse();
        assertThat(InferenceEndpoint.OPENAI_CHAT.isStreamable()).isFalse();
    }

    @Test
    void openAiEndpointsAreNotOllamaNative() {
        assertThat(InferenceEndpoint.SHOW.isOllamaNative()).isTrue();
        assertThat(InferenceEndpoint.OPENAI_EMBEDDING.isOllamaNative()).isFalse();
    }
}
