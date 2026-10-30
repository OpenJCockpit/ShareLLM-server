package io.sharellm.server.application.port.inbound;

/** Verifies bearer tokens of owners and workers. */
public interface AuthenticationUseCase {
    /**
     * @return a stable, non-reversible owner identity used to scope results
     * @throws io.sharellm.server.domain.exception.AuthenticationException if the token is not an owner key
     */
    String authenticateOwner(String token);

    /** @throws io.sharellm.server.domain.exception.AuthenticationException if the token is not the worker key */
    void authenticateWorker(String token);
}
