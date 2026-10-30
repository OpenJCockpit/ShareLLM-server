package io.sharellm.server.application.port.outbound;

/** Housekeeping operations that are safe to run concurrently on several instances. */
public interface QueueMaintenance {
    /** Requeues, or fails once the retry budget is spent, leases of lost workers and expired leases. */
    void reclaimExpiredLeases();

    /** Deletes finished jobs past their retention and long-gone workers. */
    void purgeExpired();
}
