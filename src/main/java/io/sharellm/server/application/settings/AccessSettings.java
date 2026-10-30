package io.sharellm.server.application.settings;

import java.util.List;

/** Shared secrets for owners (callers) and workers (laptops). */
public record AccessSettings(List<String> ownerKeys, String workerKey) {
    public AccessSettings {
        ownerKeys = ownerKeys == null ? List.of()
                : ownerKeys.stream().map(String::trim).filter(key -> !key.isEmpty()).toList();
        workerKey = workerKey == null ? "" : workerKey;
    }
}
