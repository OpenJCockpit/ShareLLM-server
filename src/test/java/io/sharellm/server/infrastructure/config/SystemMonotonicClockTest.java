package io.sharellm.server.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SystemMonotonicClockTest {
    private final SystemMonotonicClock clock = new SystemMonotonicClock();

    @Test
    void sleepAdvancesTheMonotonicTime() throws InterruptedException {
        long start = clock.nanoTime();
        clock.sleep(Duration.ofMillis(5));
        assertThat(clock.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(5).toNanos());
    }
}
