package io.sharellm.server.infrastructure.config;

import io.sharellm.server.application.port.inbound.AuthenticationUseCase;
import io.sharellm.server.application.port.inbound.InferenceUseCase;
import io.sharellm.server.application.port.inbound.MaintenanceUseCase;
import io.sharellm.server.application.port.inbound.WorkerUseCase;
import io.sharellm.server.application.port.outbound.InferenceQueue;
import io.sharellm.server.application.port.outbound.MonotonicClock;
import io.sharellm.server.application.port.outbound.QueueMaintenance;
import io.sharellm.server.application.port.outbound.WorkerLeases;
import io.sharellm.server.application.service.AuthenticationService;
import io.sharellm.server.application.service.InferenceService;
import io.sharellm.server.application.service.MaintenanceService;
import io.sharellm.server.application.service.WorkerService;
import io.sharellm.server.application.settings.AccessSettings;
import io.sharellm.server.application.settings.QueueSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application services to their adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShareLlmProperties.class)
public class ApplicationConfiguration {
    @Bean
    AccessSettings accessSettings(ShareLlmProperties properties) { return properties.accessSettings(); }

    @Bean
    QueueSettings queueSettings(ShareLlmProperties properties) { return properties.queueSettings(); }

    @Bean
    MonotonicClock monotonicClock() { return new SystemMonotonicClock(); }

    @Bean
    AuthenticationUseCase authenticationUseCase(AccessSettings settings) { return new AuthenticationService(settings); }

    @Bean
    InferenceUseCase inferenceUseCase(InferenceQueue queue, QueueSettings settings, MonotonicClock clock) {
        return new InferenceService(queue, settings, clock);
    }

    @Bean
    WorkerUseCase workerUseCase(WorkerLeases leases, QueueSettings settings, MonotonicClock clock) {
        return new WorkerService(leases, settings, clock);
    }

    @Bean
    MaintenanceUseCase maintenanceUseCase(QueueMaintenance maintenance) { return new MaintenanceService(maintenance); }
}
