package io.sharellm.server.adapter.inbound.web.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import io.sharellm.server.application.port.inbound.WorkerUseCase;
import io.sharellm.server.domain.exception.AuthenticationException;
import io.sharellm.server.domain.exception.LeaseConflictException;
import io.sharellm.server.domain.model.Job;
import io.sharellm.server.domain.model.WorkerRegistration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(WorkerApiController.class)
@Import(RequestAuthenticator.class)
class WorkerApiControllerTest {
    private static final String WORKER_HEADER = "Bearer worker-key";
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationUseCase authentication;
    @MockitoBean WorkerUseCase workers;

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", WORKER_HEADER)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registerReturnsTheLeaseContract() throws Exception {
        when(workers.register("laptop", List.of("m"))).thenReturn(new WorkerRegistration("laptop", 10, 45));
        send("/internal/workers/register", "{\"clientId\":\"laptop\",\"models\":[\"m\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("laptop"))
                .andExpect(jsonPath("$.heartbeatEverySeconds").value(10))
                .andExpect(jsonPath("$.leaseSeconds").value(45));
        verify(authentication).authenticateWorker("worker-key");
    }

    @Test
    void heartbeatIsAcknowledged() throws Exception {
        send("/internal/workers/heartbeat", "{\"clientId\":\"laptop\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        verify(workers).heartbeat("laptop");
    }

    @Test
    void claimReturnsTheLeasedJob() throws Exception {
        when(workers.claim("laptop", 15)).thenReturn(Optional.of(new Job(JOB_ID, "chat", "m", "{}", TOKEN, 1)));
        mvc.perform(get("/internal/jobs/claim").param("clientId", "laptop").header("Authorization", WORKER_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.endpoint").value("chat"))
                .andExpect(jsonPath("$.payload").value("{}"))
                .andExpect(jsonPath("$.leaseToken").value(TOKEN.toString()))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void claimWithoutWorkIsNoContent() throws Exception {
        when(workers.claim("laptop", 0)).thenReturn(Optional.empty());
        mvc.perform(get("/internal/jobs/claim").param("clientId", "laptop").param("waitSeconds", "0")
                        .header("Authorization", WORKER_HEADER))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void completeStoresTheResponseJson() throws Exception {
        send("/internal/jobs/" + JOB_ID + "/complete",
                "{\"clientId\":\"laptop\",\"leaseToken\":\"" + TOKEN + "\",\"response\":{\"done\":true}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.requestId").value(JOB_ID.toString()));
        verify(workers).complete(JOB_ID, "laptop", TOKEN, "{\"done\":true}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"clientId\":\"laptop\",\"response\":{}}",
        "{\"clientId\":\"laptop\",\"leaseToken\":\"00000000-0000-0000-0000-000000000004\"}",
        "{\"clientId\":\"laptop\",\"leaseToken\":\"00000000-0000-0000-0000-000000000004\",\"response\":\"text\"}"})
    void completeRequiresATokenAndAnObjectResponse(String body) throws Exception {
        send("/internal/jobs/" + JOB_ID + "/complete", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("leaseToken and object response required"));
        verifyNoInteractions(workers);
    }

    @Test
    void staleCompletionsConflict() throws Exception {
        doThrow(new LeaseConflictException("Stale or lost lease"))
                .when(workers).complete(any(), any(), any(), any());
        send("/internal/jobs/" + JOB_ID + "/complete",
                "{\"clientId\":\"laptop\",\"leaseToken\":\"" + TOKEN + "\",\"response\":{}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Stale or lost lease"));
    }

    @Test
    void failureIsAcknowledged() throws Exception {
        send("/internal/jobs/" + JOB_ID + "/fail",
                "{\"clientId\":\"laptop\",\"leaseToken\":\"" + TOKEN + "\",\"error\":\"out of memory\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("acknowledged"));
        verify(workers).fail(JOB_ID, "laptop", TOKEN, "out of memory");
    }

    @Test
    void failureRequiresALeaseToken() throws Exception {
        send("/internal/jobs/" + JOB_ID + "/fail", "{\"clientId\":\"laptop\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("leaseToken required"));
        verifyNoInteractions(workers);
    }

    @Test
    void workerRoutesRequireTheWorkerKey() throws Exception {
        doThrow(new AuthenticationException("Invalid worker token")).when(authentication).authenticateWorker("");
        mvc.perform(get("/internal/jobs/claim").param("clientId", "laptop"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid worker token"));
        verify(workers, never()).claim(any(), anyInt());
    }
}
