package io.sharellm.server.application.service;

import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import io.sharellm.server.application.settings.AccessSettings;
import io.sharellm.server.domain.exception.AuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Compares tokens in constant time; owners are identified by the SHA-256 hash of their key. */
public class AuthenticationService implements AuthenticationUseCase {
    static final String OWNER_DIGEST_ALGORITHM = "SHA-256";

    private final AccessSettings settings;

    public AuthenticationService(AccessSettings settings) { this.settings = settings; }

    @Override
    public String authenticateOwner(String token) {
        String candidate = token == null ? "" : token;
        boolean valid = false;
        // Check every key so the response time does not reveal which key matched.
        for (String key : settings.ownerKeys()) {
            valid |= equalsConstantTime(candidate, key);
        }
        // AccessSettings drops blank keys, so a blank token never matches.
        if (!valid) throw new AuthenticationException("Invalid owner token");
        return digest(candidate, OWNER_DIGEST_ALGORITHM);
    }

    @Override
    public void authenticateWorker(String token) {
        String candidate = token == null ? "" : token;
        if (candidate.isBlank() || !equalsConstantTime(candidate, settings.workerKey()))
            throw new AuthenticationException("Invalid worker token");
    }

    private static boolean equalsConstantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String digest(String text, String algorithm) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
