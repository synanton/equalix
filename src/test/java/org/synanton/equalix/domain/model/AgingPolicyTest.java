package org.synanton.equalix.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AgingPolicyTest {

    private static final double LAMBDA = 10.0;
    private static final double GAMMA = 2.0;

    @Test
    void shouldGiveNoCreditWithNonePolicy() {
        assertThat(AgingPolicy.NONE.credit(3600.0, LAMBDA, GAMMA)).isZero();
    }

    @Test
    void shouldGrowLinearly() {
        assertThat(AgingPolicy.LINEAR.credit(30.0, LAMBDA, GAMMA)).isEqualTo(300.0);
    }

    @Test
    void shouldGrowLogarithmically() {
        assertThat(AgingPolicy.LOG.credit(Math.E - 1, LAMBDA, GAMMA)).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void shouldGrowAsPowerOfWait() {
        assertThat(AgingPolicy.POWER.credit(30.0, LAMBDA, GAMMA)).isEqualTo(9000.0);
        assertThat(AgingPolicy.POWER.credit(16.0, LAMBDA, 0.5)).isEqualTo(40.0);
    }

    @ParameterizedTest
    @EnumSource(AgingPolicy.class)
    void shouldBeZeroAtZeroWaitAndNonDecreasing(AgingPolicy policy) {
        double[] waits = {0.0, 0.5, 1.0, 10.0, 60.0, 600.0, 3600.0};
        double[] credits = Arrays.stream(waits).map(wait -> policy.credit(wait, LAMBDA, GAMMA)).toArray();

        assertThat(credits[0]).isZero();
        for (int index = 1; index < credits.length; index++) {
            assertThat(credits[index]).isGreaterThanOrEqualTo(credits[index - 1]);
        }
    }

    @Test
    void shouldPromoteLongWaitsHarderWithPowerThanLogarithm() {
        // At equal credit after 10 s, power (γ=2) must dominate log for longer waits.
        double logLambda = 100.0 / Math.log1p(10.0);
        double powerLambda = 1.0;

        assertThat(AgingPolicy.POWER.credit(10.0, powerLambda, GAMMA))
            .isCloseTo(AgingPolicy.LOG.credit(10.0, logLambda, GAMMA), within(1e-9));
        assertThat(AgingPolicy.POWER.credit(600.0, powerLambda, GAMMA))
            .isGreaterThan(100 * AgingPolicy.LOG.credit(600.0, logLambda, GAMMA));
    }
}
