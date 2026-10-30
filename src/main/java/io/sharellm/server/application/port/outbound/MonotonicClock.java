package io.sharellm.server.application.port.outbound;

import java.time.Duration;

/** Elapsed-time source for bounded waits, replaceable in tests. */
public interface MonotonicClock {
    long nanoTime();

    void sleep(Duration duration) throws InterruptedException;
}
