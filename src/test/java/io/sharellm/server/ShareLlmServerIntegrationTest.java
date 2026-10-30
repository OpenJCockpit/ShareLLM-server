package io.sharellm.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives the whole server over HTTP, with the test playing the laptop worker. */
class ShareLlmServerIntegrationTest extends PostgresIntegrationTest {
    private static final String OWNER_A = "Bearer owner-a";
    private static final String OWNER_B = "Bearer owner-b";
    private static final String WORKER = "Bearer worker-key";
    private static final String ANSWER =
            "{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\",\"content\":\"hello back\"},\"done\":true}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private ResultActions send(String path, String authorization, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void registerWorker() throws Exception {
        send("/internal/workers/register", WORKER, "{\"clientId\":\"laptop\",\"models\":[\"llama3\"]}")
                .andExpect(status().isOk());
    }

    /** Long-polls until a job arrives, then answers it like the laptop client would. */
    private JsonNode workOneJob() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            MvcResult claim = mvc.perform(get("/internal/jobs/claim").param("clientId", "laptop")
                    .param("waitSeconds", "2").header("Authorization", WORKER)).andReturn();
            if (claim.getResponse().getStatus() == 200) {
                JsonNode job = mapper.readTree(claim.getResponse().getContentAsString());
                send("/internal/jobs/" + job.path("id").asString("") + "/complete", WORKER,
                        "{\"clientId\":\"laptop\",\"leaseToken\":\"" + job.path("leaseToken").asString("")
                                + "\",\"response\":" + ANSWER + "}")
                        .andExpect(status().isOk());
                return job;
            }
        }
        throw new AssertionError("No job was offered to the worker");
    }

    @Test
    void healthNeedsNoCredentials() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void synchronousChatIsAnsweredByAPollingWorker() throws Exception {
        registerWorker();
        CompletableFuture<JsonNode> worker = CompletableFuture.supplyAsync(() -> {
            try {
                return workOneJob();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        send("/v1/chat/completions", OWNER_A, "{\"model\":\"llama3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("hello back"));
        JsonNode job = worker.get(30, TimeUnit.SECONDS);
        assertThat(job.path("endpoint").asString("")).isEqualTo("openai-chat");
        assertThat(job.path("model").asString("")).isEqualTo("llama3");
    }

    @Test
    void asyncResultsAreScopedToTheOwnerKey() throws Exception {
        registerWorker();
        MvcResult accepted = send("/sharellm/requests", OWNER_A,
                "{\"endpoint\":\"chat\",\"request\":{\"model\":\"llama3\",\"messages\":[]}}")
                .andExpect(status().isAccepted()).andReturn();
        String location = accepted.getResponse().getHeader("Location");

        mvc.perform(get(location).header("Authorization", OWNER_B)).andExpect(status().isNotFound());
        mvc.perform(get(location).header("Authorization", OWNER_A))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("READY"));

        workOneJob();
        mvc.perform(get(location).header("Authorization", OWNER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.message.content").value("hello back"));
    }

    @Test
    void liveWorkerModelsAreDiscoverable() throws Exception {
        registerWorker();
        mvc.perform(get("/api/tags").header("Authorization", OWNER_B))
                .andExpect(jsonPath("$.models[0].name").value("llama3"));
        send("/internal/workers/heartbeat", WORKER, "{\"clientId\":\"laptop\"}").andExpect(status().isOk());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mvc.perform(get("/v1/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/models").header("Authorization", WORKER)).andExpect(status().isUnauthorized());
        send("/internal/workers/heartbeat", OWNER_A, "{\"clientId\":\"laptop\"}").andExpect(status().isUnauthorized());
    }
}
