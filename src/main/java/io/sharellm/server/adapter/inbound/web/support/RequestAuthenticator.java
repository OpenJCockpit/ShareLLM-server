package io.sharellm.server.adapter.inbound.web.support;

import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import org.springframework.stereotype.Component;

/** Authenticates the {@code Authorization} header of an HTTP request. */
@Component
public class RequestAuthenticator {
    private final AuthenticationUseCase authentication;

    public RequestAuthenticator(AuthenticationUseCase authentication) { this.authentication = authentication; }

    /** @return the owner identity that scopes the caller's requests */
    public String owner(String authorizationHeader) {
        return authentication.authenticateOwner(BearerToken.from(authorizationHeader));
    }

    public void worker(String authorizationHeader) {
        authentication.authenticateWorker(BearerToken.from(authorizationHeader));
    }
}
