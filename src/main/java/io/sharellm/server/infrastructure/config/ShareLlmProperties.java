package io.sharellm.server.infrastructure.config;

import io.sharellm.server.application.settings.AccessSettings;
import io.sharellm.server.application.settings.QueueSettings;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sharellm.*} configuration; see application.yml for the environment variables.
 * Invalid credentials stop the application at startup instead of leaving it open or unusable.
 */
@ConfigurationProperties(prefix = "sharellm")
public record ShareLlmProperties(
        List<String> publicKeys,
        String workerKey,
        int heartbeatTimeoutSeconds,
        int leaseSeconds,
        int maxAttempts,
        int waitSeconds,
        int pollSeconds,
        int resultPollMillis,
        int retentionHours,
        int queueLimit,
        int maxBodyBytes) {

    public ShareLlmProperties {
        publicKeys = publicKeys == null ? List.of()
                : publicKeys.stream().map(String::trim).filter(key -> !key.isEmpty()).toList();
        workerKey = workerKey == null ? "" : workerKey.trim();
        if (publicKeys.isEmpty())
            throw new IllegalArgumentException("Set at least one owner key in SHARELLM_PUBLIC_KEYS (sharellm.public-keys)");
        if (workerKey.isEmpty())
            throw new IllegalArgumentException("Set the worker key in SHARELLM_WORKER_KEY (sharellm.worker-key)");
        if (publicKeys.contains(workerKey))
            throw new IllegalArgumentException("SHARELLM_WORKER_KEY must differ from every owner key");
    }

    public AccessSettings accessSettings() { return new AccessSettings(publicKeys, workerKey); }

    public QueueSettings queueSettings() {
        return new QueueSettings(heartbeatTimeoutSeconds, leaseSeconds, maxAttempts, waitSeconds, pollSeconds,
                resultPollMillis, retentionHours, queueLimit, maxBodyBytes);
    }
}
