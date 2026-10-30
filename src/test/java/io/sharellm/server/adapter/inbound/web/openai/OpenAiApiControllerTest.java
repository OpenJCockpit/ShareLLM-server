package io.sharellm.server.adapter.inbound.web.openai;

import static io.sharellm.server.adapter.inbound.web.WebTestSupport.ID;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.OWNER;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.OWNER_HEADER;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.completed;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.failed;
import static io.sharellm.server.adapter.inbound.web.WebTestSupport.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
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
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(OpenAiApiController.class)
@Import({RequestAuthenticator.class, OpenAiTranslator.class})
class OpenAiApiControllerTest {
    private static final String CHAT = "{\"model\":\"llama3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]";

    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationUseCase authentication;
    @MockitoBean InferenceUseCase inference;

    @BeforeEach
    void authenticate() { when(authentication.authenticateOwner("owner-key")).thenReturn(OWNER); }

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", OWNER_HEADER)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private InferenceRequest submitted() throws InterruptedException {
        ArgumentCaptor<InferenceRequest> request = ArgumentCaptor.forClass(InferenceRequest.class);
        verify(inference).submitAndAwait(request.capture(), eq(OWNER));
        return request.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/models", "/models"})
    void modelsAreListedInOpenAiFormat(String path) throws Exception {
        when(inference.availableModels()).thenReturn(List.of("llama3"));
        mvc.perform(get(path).header("Authorization", OWNER_HEADER))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("llama3"))
                .andExpect(jsonPath("$.data[0].owned_by").value("sharellm"));
    }

    @Test
    void chatCompletionIsTranslatedBothWays() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER)))
                .thenReturn(completed("openai-chat", "llama3", "{\"message\":{\"content\":\"hello\"},\"done\":true}"));
        send("/v1/chat/completions", CHAT + "}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-ShareLLM-Request-Id", ID.toString()))
                .andExpect(jsonPath("$.id").value("chatcmpl-" + ID))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hello"));
        InferenceRequest request = submitted();
        assertThat(request.endpoint()).isEqualTo(InferenceEndpoint.OPENAI_CHAT);
        assertThat(request.model()).isEqualTo("llama3");
        assertThat(request.payloadJson()).contains("\"stream\":false").contains("\"messages\"");
    }

    @Test
    void streamedChatIsOneServerSentEvent() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER)))
                .thenReturn(completed("openai-chat", "llama3", "{\"message\":{\"content\":\"hello\"}}"));
        send("/v1/chat/completions", CHAT + ",\"stream\":true}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(startsWith("data: {")))
                .andExpect(content().string(endsWith("\n\ndata: [DONE]\n\n")))
                .andExpect(content().string(containsString("chat.completion.chunk")));
    }

    @Test
    void invalidChatIsRejectedBeforeQueueing() throws Exception {
        send("/v1/chat/completions", "{\"model\":\"m\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("messages array required"));
        verifyNoInteractions(inference);
    }

    @Test
    void completionIsTranslatedAndCanStream() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER)))
                .thenReturn(completed("openai-completion", "m", "{\"response\":\"text\"}"));
        send("/v1/completions", "{\"model\":\"m\",\"prompt\":\"p\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].text").value("text"));
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.OPENAI_COMPLETION);
        send("/v1/completions", "{\"model\":\"m\",\"prompt\":\"p\",\"stream\":true}")
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void embeddingsAreTranslated() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER)))
                .thenReturn(completed("openai-embedding", "m", "{\"embeddings\":[[0.5]]}"));
        send("/v1/embeddings", "{\"model\":\"m\",\"input\":\"x\",\"stream\":true}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data[0].embedding[0]").value(0.5));
        assertThat(submitted().endpoint()).isEqualTo(InferenceEndpoint.OPENAI_EMBEDDING);
    }

    @Test
    void unfinishedRequestsTimeOut() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(pending("openai-chat", "m"));
        send("/v1/chat/completions", CHAT + "}")
                .andExpect(status().isGatewayTimeout())
                .andExpect(header().string("Location", "/sharellm/requests/" + ID));
    }

    @Test
    void workerFailuresAreBadGateway() throws Exception {
        when(inference.submitAndAwait(any(), eq(OWNER))).thenReturn(failed("openai-embedding", "m"));
        send("/v1/embeddings", "{\"model\":\"m\",\"input\":\"x\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Worker lost; retry budget exhausted"));
    }
}
