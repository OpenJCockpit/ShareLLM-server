package io.sharellm.server.adapter.inbound.web.ollama;

import static io.sharellm.server.adapter.inbound.web.WebTestSupport.ID;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.OWNER;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.OWNER_HEADER;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.completed;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.failed;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.domain.exception.AuthenticationException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(OllamaApiController.class)
@Import(RequestAuthenticator.class)
class OllamaApiControllerTest {
    private static final String DONE = "{\"done\":true}";

    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationUseCase authentication;
    @MockitoBean InferenceUseCase inference;

    @BeforeEach
    void authenticate() {
        when(authentication.authenticateOwner("owner-key")).thenReturn(OWNER);
        when(authentication.authenticateOwner("")).thenThrow(new AuthenticationException("Invalid owner token"));
    }

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header("Authorization", OWNER_HEADER).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private InferenceRequest submitted() throws InterruptedException {
        ArgumentCaptor<InferenceRequest> request = ArgumentCaptor.forClass(InferenceRequest.class);
        verify(inference).submitAndAwait(request.capture(), eq(OWNER));
        return request.getValue();
    }

    @Test
    void chatStreamsOneNdjsonEventByDefault() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("chat", "llama3", DONE));
        send("/api/chat", "{\"model\":\"llama3\",\"messages\":[]}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON))
                .andExpect(content().string(DONE + "\n"))
                .andExpect(header().string("X-ShareLLM-Request-Id", ID.toString()));
        InferenceRequest request = submitted();
        assertThat(request.endpoint()).isEqualTo(InferenceEndpoint.CHAT);
        assertThat(request.model()).isEqualTo("llama3");
        assertThat(request.payloadJson()).contains("\"messages\":[]");
    }

    @Test
    void chatWithoutStreamingIsPlainJson() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("chat", "m", DONE));
        send("/api/chat", "{\"model\":\"m\",\"messages\":[],\"stream\":false}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(DONE));
    }

    @Test
    void chatRequiresMessages() throws Exception {
        send("/api/chat", "{\"model\":\"m\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("messages array required"));
        verifyNoInteractions(inference);
    }

    @Test
    void generateStreamsAndRejectsNonObjectBodies() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("generate", "m", DONE));
        send("/api/generate", "{\"model\":\"m\",\"prompt\":\"hi\"}")
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON));
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.GENERATE);
        send("/api/generate", "[1]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("JSON object required"));
    }

    @Test
    void embedRequiresInputAndNeverStreams() throws Exception {
        send("/api/embed", "{\"model\":\"m\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("input required"));
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("embed", "m", DONE));
        send("/api/embed", "{\"model\":\"m\",\"input\":\"x\",\"stream\":true}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.EMBED);
    }

    @Test
    void embeddingsRequirePrompt() throws Exception {
        send("/api/embeddings", "{\"model\":\"m\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("prompt required"));
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("embeddings", "m", DONE));
        send("/api/embeddings", "{\"model\":\"m\",\"prompt\":\"x\"}").andExpect(status().isOk());
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.EMBEDDINGS);
    }

    @Test
    void showIsForwarded() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(completed("show", "m", DONE));
        send("/api/show", "{\"model\":\"m\"}").andExpect(status().isOk()).andExpect(content().string(DONE));
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.SHOW);
    }

    @Test
    void unfinishedRequestsTimeOutWithAPollableLocation() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(pending("chat", "m"));
        send("/api/chat", "{\"model\":\"m\",\"messages\":[]}")
                .andExpect(status().isGatewayTimeout())
                .andExpect(header().string("Location", "/sharellm/requests/" + ID))
                .andExpect(header().string("X-ShareLLM-Request-Id", ID.toString()))
                .andExpect(jsonPath("$.request_id").value(ID.toString()));
    }

    @Test
    void workerFailuresKeepTheirStatusAndBody() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(failed("chat", "m"));
        send("/api/chat", "{\"model\":\"m\",\"messages\":[]}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Worker lost; retry budget exhausted"));
    }

    @Test
    void interruptedWaitsAreUnavailable() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenThrow(new InterruptedException());
        try {
            send("/api/generate", "{\"model\":\"m\"}").andExpect(status().isServiceUnavailable());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void requestsWithoutAnOwnerKeyAreUnauthorized() throws Exception {
        mvc.perform(get("/api/tags"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid owner token"));
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inference);
    }

    @Test
    void tagsListLiveModels() throws Exception {
        when(inference.availableModels()).thenReturn(List.of("llama3"));
        mvc.perform(get("/api/tags").header("Authorization", OWNER_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].name").value("llama3"))
                .andExpect(jsonPath("$.models[0].model").value("llama3"))
                .andExpect(jsonPath("$.models[0].size").value(0));
    }

    @Test
    void versionIsTheShareLlmVersion() throws Exception {
        mvc.perform(get("/api/version").header("Authorization", OWNER_HEADER))
                .andExpect(jsonPath("$.version").value(OllamaApiController.VERSION));
    }
}
