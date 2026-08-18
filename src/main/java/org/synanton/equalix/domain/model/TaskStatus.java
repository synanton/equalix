package org.synanton.equalix.domain.model;

/** Lifecycle states of a task from ingestion to final resolution. */
public enum TaskStatus {
    RECEIVED,
    QUEUED,
    DISPATCHED,
    COMMITTED,
    SUCCEEDED,
    FAILED,
    TIMEOUT;

    public boolean isInFlight() {
        return this == DISPATCHED || this == COMMITTED;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMEOUT;
    }
}
