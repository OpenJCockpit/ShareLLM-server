package io.sharellm.server.application.port.inbound;

/** Periodic queue housekeeping. */
public interface MaintenanceUseCase {
    void reclaimExpiredLeases();

    void purgeExpired();
}
