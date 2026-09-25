package org.synanton.equalix.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that stays fixed until a test explicitly moves it; reset before every test. */
public final class AdjustableClock extends Clock {

    private final Instant initial;
    private volatile Instant current;

    AdjustableClock(Instant initial) {
        this.initial = initial;
        this.current = initial;
    }

    public void advance(Duration duration) {
        current = current.plus(duration);
    }

    void reset() {
        current = initial;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("AdjustableClock is UTC only");
    }

    @Override
    public Instant instant() {
        return current;
    }
}
