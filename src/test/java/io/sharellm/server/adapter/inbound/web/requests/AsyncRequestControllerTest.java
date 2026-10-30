package io.sharellm.server.adapter.inbound.web.requests;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.domain.exception.ResourceNotFoundException;
import io.sharellm.server.domain.model.InferenceEndpoint;
import io.sharellm.server.domain.model.InferenceRequest;
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

@WebMvcTest(AsyncRequestController.class)
@Import(RequestAuthenticator.class)
class AsyncRequestControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationUseCase authentication;
    @MockitoBean InferenceUseCase inference;

    @BeforeEach
    void authenticate() { when(authentication.authenticateOwner("owner-key")).thenReturn(OWNER); }

    private ResultActions enqueue(String envelope) throws Exception {
        return mvc.perform(post("/sharellm/requests").header("Authorization", OWNER_HEADER)
                .contentType(MediaType.APPLICATION_JSON).content(envelope));
    }

    private ResultActions result() throws Exception {
        return mvc.perform(get("/sharellm/requests/" + ID).header("Authorization", OWNER_HEADER));
    }

    @Test
    void enqueueDefaultsToChatAndPointsToTheResult() throws Exception {
        when(inference.enqueue(any(), eq(OWNER))).thenReturn(ID);
        enqueue("{\"request\":{\"model\":\"m\",\"messages\":[]}}")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/sharellm/requests/" + ID))
                .andExpect(header().string("X-ShareLLM-Request-Id", ID.toString()))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.resultUrl").value("/sharellm/requests/" + ID));
        ArgumentCaptor<InferenceRequest> request = ArgumentCaptor.forClass(InferenceRequest.class);
        verify(inference).enqueue(request.capture(), eq(OWNER));
        assertThat(request.getValue())
                .isEqualTo(new InferenceRequest(InferenceEndpoint.CHAT, "m", "{\"model\":\"m\",\"messages\":[]}"));
    }

    @Test
    void enqueueAcceptsOtherOllamaEndpoints() throws Exception {
        when(inference.enqueue(any(), eq(OWNER))).thenReturn(ID);
        enqueue("{\"endpoint\":\"embed\",\"request\":{\"model\":\"m\",\"input\":\"x\"}}").andExpect(status().isAccepted());
        ArgumentCaptor<InferenceRequest> request = ArgumentCaptor.forClass(InferenceRequest.class);
        verify(inference).enqueue(request.capture(), eq(OWNER));
        assertThat(request.getValue().endpoint()).isEqualTo(InferenceEndpoint.EMBED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-chat", "pull"})
    void enqueueRejectsOtherEndpoints(String endpoint) throws Exception {
        enqueue("{\"endpoint\":\"" + endpoint + "\",\"request\":{\"model\":\"m\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported endpoint"));
        verifyNoInteractions(inference);
    }

    @Test
    void enqueueRequiresARequestObject() throws Exception {
        enqueue("{\"endpoint\":\"chat\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("JSON object required"));
    }

    @Test
    void unfinishedResultIsAccepted() throws Exception {
        when(inference.findResult(ID, OWNER)).thenReturn(pending("chat", "m").result());
        result().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("LEASED"));
    }

    @Test
    void completedResultEmbedsTheOllamaResponse() throws Exception {
        when(inference.findResult(ID, OWNER)).thenReturn(completed("chat", "m", "{\"done\":true}").result());
        result().andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endpoint").value("chat"))
                .andExpect(jsonPath("$.model").value("m"))
                .andExpect(jsonPath("$.result.done").value(true));
    }

    @Test
    void failedResultIsABadGateway() throws Exception {
        when(inference.findResult(ID, OWNER)).thenReturn(failed("chat", "m").result());
        result().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.result.error").value("Worker lost; retry budget exhausted"));
    }

    @Test
    void unknownResultIsNotFound() throws Exception {
        when(inference.findResult(ID, OWNER)).thenThrow(new ResourceNotFoundException("Unknown request"));
        result().andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Unknown request"));
    }
}
