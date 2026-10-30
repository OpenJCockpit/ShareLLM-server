package io.sharellm.server.adapter.inbound.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.sharellm.server.application.port.inbound.MaintenanceUseCase;
import org.junit.jupiter.api.Test;

class QueueMaintenanceSchedulerTest {
    private final MaintenanceUseCase maintenance = mock(MaintenanceUseCase.class);
    private final QueueMaintenanceScheduler scheduler = new QueueMaintenanceScheduler(maintenance);

    @Test
    void triggersHousekeeping() {
        scheduler.reclaimExpiredLeases();
        scheduler.purgeExpired();
        verify(maintenance).reclaimExpiredLeases();
        verify(maintenance).purgeExpired();
    }
}
