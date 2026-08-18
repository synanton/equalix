package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;

class AdaptiveRpsControllerTest {

    private AdaptiveRpsProperties props;
    private AdaptiveRpsController controller;

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
        controller = new AdaptiveRpsController(props);
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
}
