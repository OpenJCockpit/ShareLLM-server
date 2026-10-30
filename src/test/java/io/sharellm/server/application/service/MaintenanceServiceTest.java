package io.sharellm.server.application.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.sharellm.server.application.port.outbound.QueueMaintenance;
import org.junit.jupiter.api.Test;

class MaintenanceServiceTest {
    private final QueueMaintenance maintenance = mock(QueueMaintenance.class);
    private final MaintenanceService service = new MaintenanceService(maintenance);

    @Test
    void delegatesToThePort() {
        service.reclaimExpiredLeases();
        service.purgeExpired();
        verify(maintenance).reclaimExpiredLeases();
        verify(maintenance).purgeExpired();
    }
}
