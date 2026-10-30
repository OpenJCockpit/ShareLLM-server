package io.sharellm.server.application.service;

import io.sharellm.server.application.port.inbound.MaintenanceUseCase;
import io.sharellm.server.application.port.outbound.QueueMaintenance;

public class MaintenanceService implements MaintenanceUseCase {
    private final QueueMaintenance maintenance;

    public MaintenanceService(QueueMaintenance maintenance) { this.maintenance = maintenance; }

    @Override
    public void reclaimExpiredLeases() { maintenance.reclaimExpiredLeases(); }

    @Override
    public void purgeExpired() { maintenance.purgeExpired(); }
}
