package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;

class AdaptiveRpsControllerTest {

    private AdaptiveRpsProperties props;
    private AdaptiveRpsController controller;
    private ManualClock clock;

    @BeforeEach
    void setUp() {
        props = new AdaptiveRpsProperties();
        props.setEnabled(true);
        props.setInitialRps(10.0);
        props.setMinRps(1.0);
        props.setMaxRps(100.0);
        props.setTargetLatencyMs(200);
        props.setErrorThreshold(0.05);
        props.setWindowSize(10);
        props.setMinSamples(10);
        props.setLatencyThreshold(0.1);
        props.setEmergencyFactor(0.5);
        props.setDecreaseFactor(0.9);
        props.setIncreaseFactor(1.05);
        props.setIncreaseErrorThreshold(0.01);
        // Pre-EQX-6 behaviour: no smoothing, adjust on every completion, reverse immediately.
        props.setLatencyEmaAlpha(1.0);
        props.setAdjustmentIntervalMs(0);
        props.setDirectionChangeConfirmations(1);
        clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        controller = new AdaptiveRpsController(props, clock);
    }

    @Test
    void shouldIncreaseRpsWhenLatencyLowAndErrorRateNegligible() {
        for (int idx = 0; idx < 100; idx++) {
            controller.recordCompletion(100, true);
        }

        assertThat(controller.getCurrentRps()).isGreaterThan(10.0);
    }

    @Test
    void shouldDecreaseRpsWhenLatencyHigh() {
        for (int idx = 0; idx < 100; idx++) {
            controller.recordCompletion(300, true);
        }

        assertThat(controller.getCurrentRps()).isLessThan(10.0);
    }

    @Test
    void shouldEmergencyBrakeOnHighErrorRate() {
        double initialRps = controller.getCurrentRps();

        for (int idx = 0; idx < 100; idx++) {
            controller.recordCompletion(100, idx < 10);
        }

        assertThat(controller.getCurrentRps()).isLessThan(initialRps);
    }

    @Test
    void shouldNeverExceedMaxRps() {
        for (int idx = 0; idx < 1000; idx++) {
            controller.recordCompletion(50, true);
        }

        assertThat(controller.getCurrentRps()).isLessThanOrEqualTo(props.getMaxRps());
    }

    @Test
    void shouldNeverDropBelowMinimumRps() {
        for (int idx = 0; idx < 1000; idx++) {
            controller.recordCompletion(1000, false);
        }

        assertThat(controller.getCurrentRps()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void shouldReturnPenaltyFactorAsOneThousandDividedByCurrentRps() {
        double expectedPenalty = 1000.0 / props.getInitialRps();

        assertThat(controller.getPenaltyFactor()).isEqualTo(expectedPenalty);
    }

    @Test
    void shouldReproducePreEqx6StepsWhenStabilityControlsAreOff() {
        // Stock settings: every completion past min-samples re-evaluates the whole window and steps again.
        feed(controller, 10, 300, true);
        assertThat(controller.getCurrentRps()).isCloseTo(9.0, within(1e-9));

        feed(controller, 2, 300, true);

        assertThat(controller.getCurrentRps()).isCloseTo(9.0 * 0.9 * 0.9, within(1e-9));
    }

    @Test
    void shouldAdjustAtMostOncePerInterval() {
        AdaptiveRpsController stable = controller(1.0, 1_000, 1);

        feed(stable, 50, 300, true);
        assertThat(stable.getCurrentRps()).isCloseTo(9.0, within(1e-9));

        clock.advance(Duration.ofMillis(999));
        feed(stable, 1, 300, true);
        assertThat(stable.getCurrentRps()).isCloseTo(9.0, within(1e-9));

        clock.advance(Duration.ofMillis(1));
        feed(stable, 1, 300, true);
        assertThat(stable.getCurrentRps()).isCloseTo(8.1, within(1e-9));
    }

    @Test
    void shouldUseOnlyCompletionsSinceLastEvaluationWhenIntervalIsSet() {
        AdaptiveRpsController stable = controller(1.0, 1_000, 1);
        feed(stable, 10, 300, true);                   // evaluation 1: decrease
        clock.advance(Duration.ofSeconds(1));

        feed(stable, 1, 100, true);                    // evaluation 2 sees only this fast sample: increase

        assertThat(stable.getCurrentRps()).isCloseTo(9.0 * 1.05, within(1e-9));
    }

    @Test
    void shouldSmoothLatencyWithEma() {
        AdaptiveRpsController raw = controller(1.0, 1_000, 1);
        AdaptiveRpsController smoothed = controller(0.5, 1_000, 1);
        for (AdaptiveRpsController candidate : List.of(raw, smoothed)) {
            feed(candidate, 10, 300, true);            // both decrease to 9.0
        }
        clock.advance(Duration.ofSeconds(1));

        // Fresh mean 100 ms: raw signal is 100 (below band, increase); smoothed is 0.5*100 + 0.5*300 = 200 (hold).
        feed(raw, 1, 100, true);
        feed(smoothed, 1, 100, true);

        assertThat(List.of(raw.getCurrentRps(), smoothed.getCurrentRps()))
            .containsExactly(9.0 * 1.05, 9.0);
    }

    @Test
    void shouldRequireConsecutiveConfirmationsBeforeReversingDirection() {
        AdaptiveRpsController dampened = controller(1.0, 1_000, 3);
        feed(dampened, 10, 300, true);                 // decrease to 9.0
        List<Double> rates = new ArrayList<>();

        for (int evaluation = 0; evaluation < 3; evaluation++) {
            clock.advance(Duration.ofSeconds(1));
            feed(dampened, 1, 100, true);
            rates.add(dampened.getCurrentRps());
        }

        assertThat(rates).containsExactly(9.0, 9.0, 9.0 * 1.05);
    }

    @Test
    void shouldResetConfirmationsWhenLatencyReturnsToDeadBand() {
        AdaptiveRpsController dampened = controller(1.0, 1_000, 2);
        feed(dampened, 10, 300, true);                 // decrease to 9.0

        clock.advance(Duration.ofSeconds(1));
        feed(dampened, 1, 100, true);                  // first confirmation of an increase
        clock.advance(Duration.ofSeconds(1));
        feed(dampened, 1, 200, true);                  // dead band: count resets
        clock.advance(Duration.ofSeconds(1));
        feed(dampened, 1, 100, true);                  // first confirmation again

        assertThat(dampened.getCurrentRps()).isCloseTo(9.0, within(1e-9));
    }

    @Test
    void shouldContinueInSameDirectionWithoutConfirmation() {
        AdaptiveRpsController dampened = controller(1.0, 1_000, 3);
        feed(dampened, 10, 300, true);
        clock.advance(Duration.ofSeconds(1));

        feed(dampened, 1, 300, true);

        assertThat(dampened.getCurrentRps()).isCloseTo(8.1, within(1e-9));
    }

    @Test
    void shouldApplyEmergencyBrakeWithoutDampeningButOncePerInterval() {
        AdaptiveRpsController dampened = controller(1.0, 1_000, 3);
        feed(dampened, 10, 100, true);                 // increase to 10.5
        clock.advance(Duration.ofSeconds(1));

        feed(dampened, 5, 100, false);                 // 5 of 11 failed: brake immediately, once this interval

        assertThat(dampened.getCurrentRps()).isCloseTo(10.5 * 0.5, within(1e-9));
    }

    @Test
    void shouldRejectInvalidStabilitySettings() {
        props.setLatencyEmaAlpha(0.0);
        assertThatThrownBy(() -> new AdaptiveRpsController(props, clock)).isInstanceOf(IllegalArgumentException.class);
        props.setLatencyEmaAlpha(1.0);
        props.setAdjustmentIntervalMs(-1);
        assertThatThrownBy(() -> new AdaptiveRpsController(props, clock)).isInstanceOf(IllegalArgumentException.class);
        props.setAdjustmentIntervalMs(0);
        props.setDirectionChangeConfirmations(0);
        assertThatThrownBy(() -> new AdaptiveRpsController(props, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    private AdaptiveRpsController controller(double alpha, long intervalMs, int confirmations) {
        props.setLatencyEmaAlpha(alpha);
        props.setAdjustmentIntervalMs(intervalMs);
        props.setDirectionChangeConfirmations(confirmations);
        return new AdaptiveRpsController(props, clock);
    }

    private static void feed(AdaptiveRpsController target, int completions, long latencyMs, boolean success) {
        for (int completion = 0; completion < completions; completion++) {
            target.recordCompletion(latencyMs, success);
        }
    }
}
