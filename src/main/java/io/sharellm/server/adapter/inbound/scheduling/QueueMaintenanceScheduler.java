package io.sharellm.server.adapter.inbound.scheduling;

import io.sharellm.server.application.port.inbound.MaintenanceUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers queue housekeeping on every instance; the operations are safe to run concurrently. */
@Component
public class QueueMaintenanceScheduler {
    private final MaintenanceUseCase maintenance;

    public QueueMaintenanceScheduler(MaintenanceUseCase maintenance) { this.maintenance = maintenance; }

    @Scheduled(fixedDelayString = "${sharellm.reclaim-interval-ms:2000}")
    public void reclaimExpiredLeases() { maintenance.reclaimExpiredLeases(); }

    @Scheduled(fixedDelayString = "${sharellm.cleanup-interval-ms:3600000}")
    public void purgeExpired() { maintenance.purgeExpired(); }
}
