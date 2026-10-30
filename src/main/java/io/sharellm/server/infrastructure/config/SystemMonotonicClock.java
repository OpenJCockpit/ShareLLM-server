package io.sharellm.server.infrastructure.config;

import io.sharellm.server.application.port.outbound.MonotonicClock;
import java.time.Duration;

public class SystemMonotonicClock implements MonotonicClock {
    @Override
    public long nanoTime() { return System.nanoTime(); }

    @Override
    public void sleep(Duration duration) throws InterruptedException { Thread.sleep(duration); }
}
