package io.sharellm.server.domain.model;

/** Lifecycle of a job: READY -> LEASED -> COMPLETED, or back to READY until the retry budget is spent. */
public enum JobState { READY, LEASED, COMPLETED, FAILED }
