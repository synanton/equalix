package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that only moves when told to. */
final class ManualClock extends Clock {

    private Instant current;

    ManualClock(Instant start) {
        this.current = start;
    }

    void advance(Duration duration) {
        current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("ManualClock is UTC only");
    }

    @Override
    public Instant instant() {
        return current;
    }
}
