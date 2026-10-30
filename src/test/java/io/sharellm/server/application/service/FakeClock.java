package io.sharellm.server.application.service;

import io.sharellm.server.application.port.outbound.MonotonicClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A clock whose time only advances when the code under test sleeps. */
final class FakeClock implements MonotonicClock {
    final List<Duration> sleeps = new ArrayList<>();
    private long now;

    @Override
    public long nanoTime() { return now; }

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
        now += duration.toNanos();
    }
}
