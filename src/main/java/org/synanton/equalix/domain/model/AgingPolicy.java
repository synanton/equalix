package org.synanton.equalix.domain.model;

/**
 * Anti-starvation aging function A(W) from mathematical invariants §10 and §25.3. The credit is subtracted from
 * a task's base priority, so waiting tasks move forward. {@code W} is the waiting time in seconds.
 */
public enum AgingPolicy {

    /** No aging; only the {@code max-queued-time-ms} promotion applies. */
    NONE {
        @Override
        public double credit(double waitSeconds, double lambda, double gamma) {
            return 0.0;
        }
    },

    /** {@code A(W) = λ W}: constant pull per second of waiting. */
    LINEAR {
        @Override
        public double credit(double waitSeconds, double lambda, double gamma) {
            return lambda * waitSeconds;
        }
    },

    /** {@code A(W) = λ log(1 + W)}: strong early boost that flattens, bounding how far any task can jump. */
    LOG {
        @Override
        public double credit(double waitSeconds, double lambda, double gamma) {
            return lambda * Math.log1p(waitSeconds);
        }
    },

    /**
     * {@code A(W) = λ W^γ}. With {@code γ > 1} short waits barely matter while long waits are promoted
     * aggressively; {@code γ < 1} behaves like a softer logarithm.
     */
    POWER {
        @Override
        public double credit(double waitSeconds, double lambda, double gamma) {
            return lambda * Math.pow(waitSeconds, gamma);
        }
    };

    /**
     * Returns the aging credit in priority units for a task that has waited {@code waitSeconds}.
     *
     * @param waitSeconds non-negative waiting time
     * @param lambda aging rate λ, in priority units
     * @param gamma exponent γ, used by {@link #POWER} only
     */
    public abstract double credit(double waitSeconds, double lambda, double gamma);
}
