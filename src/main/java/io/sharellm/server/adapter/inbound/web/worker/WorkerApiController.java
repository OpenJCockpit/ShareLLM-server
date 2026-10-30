package io.sharellm.server.adapter.inbound.web.worker;

import io.sharellm.server.adapter.inbound.web.support.RequestAuthenticator;
import io.sharellm.server.application.port.inbound.WorkerUseCase;
import io.sharellm.server.domain.exception.InvalidRequestException;
import io.sharellm.server.domain.model.WorkerRegistration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Routes for outbound-only laptop workers. */
@RestController
@RequestMapping("/internal")
public class WorkerApiController {
    private final RequestAuthenticator authenticator;
    private final WorkerUseCase workers;

    public WorkerApiController(RequestAuthenticator authenticator, WorkerUseCase workers) {
        this.authenticator = authenticator;
        this.workers = workers;
    }

    @PostMapping("/workers/register")
    public Map<String, Object> register(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody WorkerRegistrationRequest request) {
        authenticator.worker(authorization);
        WorkerRegistration registration = workers.register(request.clientId(), request.models());
        return Map.of("clientId", registration.clientId(),
                "heartbeatEverySeconds", registration.heartbeatEverySeconds(),
                "leaseSeconds", registration.leaseSeconds());
    }

    @PostMapping("/workers/heartbeat")
    public Map<String, String> heartbeat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody HeartbeatRequest request) {
        authenticator.worker(authorization);
        workers.heartbeat(request.clientId());
        return Map.of("status", "ok");
    }

    /** Long poll: 200 with a leased job, or 204 when none became available in time. */
    @GetMapping("/jobs/claim")
    public ResponseEntity<ClaimedJobResponse> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String clientId,
            @RequestParam(defaultValue = "15") int waitSeconds) throws InterruptedException {
        authenticator.worker(authorization);
        return workers.claim(clientId, waitSeconds).map(ClaimedJobResponse::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/complete")
    public Map<String, String> complete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable UUID id, @RequestBody CompletionRequest request) {
        authenticator.worker(authorization);
        if (request.leaseToken() == null || request.response() == null || !request.response().isObject())
            throw new InvalidRequestException("leaseToken and object response required");
        workers.complete(id, request.clientId(), request.leaseToken(), request.response().toString());
        return Map.of("status", "accepted", "requestId", id.toString());
    }

    @PostMapping("/jobs/{id}/fail")
    public Map<String, String> fail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable UUID id, @RequestBody FailureRequest request) {
        authenticator.worker(authorization);
        if (request.leaseToken() == null) throw new InvalidRequestException("leaseToken required");
        workers.fail(id, request.clientId(), request.leaseToken(), request.error());
        return Map.of("status", "acknowledged");
    }
}
