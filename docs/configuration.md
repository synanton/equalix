# Equalix configuration reference

All configuration lives in `application.yml`. Defaults shown below match
`src/main/resources/application.yml`.

## Security

```yaml
app:
  security:
    api-key: ${EQUALIX_API_KEY:changeme}   # Required on X-API-Key for /api and prometheus
```

Set `EQUALIX_API_KEY` in every non-local environment. Datasource credentials come from
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

## Scheduling

```yaml
app:
  scheduling:
    enabled: true   # Runs the priority calculator, dispatchers, watchdog and recovery jobs
```

Set it to `false` for a node that should only ingest and receive completions. Integration tests also set it
to `false` and drive the jobs directly.

## Root

```yaml
app:
  executor:
    base-url: http://localhost:9090   # Remote executor base URL
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```

`base-url` is the target that `HttpRemoteExecutorAdapter` posts task envelopes to
(`{base-url}/tasks/{id}/execute`).

## Queue

```yaml
app:
  queue:
    max-tasks-in-process: 5000        # Global concurrency cap
    max-per-client-quota: 500         # Hard quota per fairness key (0 = disabled)
    priority-calc-interval: 100       # ms - RECEIVED → QUEUED cadence
    dispatcher-interval: 50           # ms - QUEUED → DISPATCHED cadence
    worker-poll-size: 100             # Batch size for priority calc / starved-task promotion
    max-queued-time-ms: 60000         # Anti-starvation deadline
    task-timeout-ms: 300000           # In-flight tasks older than this become TIMEOUT (0 = off)
    max-payload-bytes: 1048576        # Ingest payload cap
    virtual-time:
      quantum: 1000                   # Virtual-time units charged per task at weight 1.0 (advance = quantum / weight)
    aging:
      policy: none                    # none | linear | log | power
      lambda: 1000                    # Aging rate λ in priority units
      gamma: 2.0                      # Exponent γ for power aging
      candidate-pool-size: 200        # Rows locked per candidate ordering when aging is on
```

`aging` applies an anti-starvation credit A(W) when the dispatcher selects tasks: effective priority is
`priority − A(W)`, where W is the number of seconds since the task was created.

| Policy | A(W) | Behaviour |
|--------|------|-----------|
| `none` | 0 | Default. Only the `max-queued-time-ms` promotion applies |
| `linear` | λ·W | Constant pull per second of waiting |
| `log` | λ·ln(1+W) | Early boost that flattens; bounded disruption, weak promotion of long waits |
| `power` | λ·W^γ | With γ > 1, short waits barely matter and long waits are promoted aggressively |

One weight-1 task is worth `virtual-time.quantum` priority units, so the policies are easiest to compare by
calibrating λ to a credit at a target wait. For example, `A(30 s) = 10 × quantum` gives λ = 333 (linear),
2912 (log), or 11.1 (power, γ = 2). With aging on, the dispatcher locks the best `candidate-pool-size` tasks
by stored priority and the oldest `candidate-pool-size` tasks, then re-ranks them by aged priority.
Increase the pool if heavy backlogs make the aged order approximate. `max-queued-time-ms` stays active as a
hard backstop for every policy.

`virtual-time.quantum` sets the scale of the persistent fairness term relative to the in-flight
pressure term (`1000 / currentRps` per in-flight task). Larger values make historical weighted
allocation dominate current load; smaller values let in-flight pressure matter more.

`max-per-client-quota = 0` disables the per-key hard ceiling; the priority formula still
deprioritizes heavy keys, but nothing prevents them from monopolizing dispatch slots.

When `app.adaptive-rps.enabled` is true, each dispatcher tick is also capped by
`ceil(currentRps × dispatcher-interval / 1000)`.

## Count-Min Sketch

```yaml
app:
  queue:
    cms:
      mode: local                     # 'local' or 'redis'
      width: 65536                    # ε = 2 / width
      depth: 5                        # δ = (1/2)^depth
      redis:
        key-namespace: equalix:cms    # Redis keys: {namespace}:v2 and {namespace}:v2:total
        fallback-to-local: true       # Fall back to in-memory sketch if Redis is down
      error-sampling:
        enabled: false                # Sample e_k = estimate - in-flight tasks (load tests)
        interval-ms: 1000
```

The CMS error depends on the number of tasks **currently in flight** (N), not on the number of keys. It never
underestimates, and it overestimates by at most `2N / width` with probability `1 - 2^-depth`. With the
defaults (65536×5) and 5,000 tasks in flight, measured error was 0 even with 50,000 keys. See
[mathematical invariants §20](src/mathematical-invariants3.md) for the measured distribution.

`error-sampling.enabled` turns on `CmsErrorSamplingScheduler`, which compares the sketch with the task table
every `interval-ms` and publishes `equalix.cms.estimation.error{direction}` and
`equalix.cms.estimation.error.magnitude` (p50/p95/p99). Each sample runs one `GROUP BY` over in-flight tasks,
so it is meant for load tests rather than always-on use. With the local CMS, each instance samples its own
sketch.

Sizing guidance:

| Active fairness keys | `width`  | `depth` | Memory  | Expected error |
|----------------------|----------|---------|---------|----------------|
| ≤ 10 000             | 65 536   | 5       | ~2.6 MB | < 0.003 %      |
| ≤ 100 000            | 131 072  | 5       | ~5 MB   | < 0.0015 %     |

`depth > 5` shows diminishing returns.

`mode: redis` requires reachable Redis. `RedisCMSAdapter` is selected instead of the local sketch.

## Sequential execution

```yaml
app:
  queue:
    sequential:
      enabled: true
      client-block-timeout-ms: 60000       # Auto-unblock a fairness key after this delay
      dispatcher-interval: 50              # Sequential dispatcher cadence (ms)
      block-recovery-interval: 10000       # ClientBlockRecoveryService cadence (ms)
      result-passthrough-interval: 60000   # ResultPassthroughRecoveryService cadence (ms)
```

## Adaptive RPS

```yaml
app:
  adaptive-rps:
    enabled: true
    initial-rps: 1                         # Starting cap; ramps up
    min-rps: 1                             # Absolute floor (never go below)
    max-rps: 100                           # Absolute ceiling
    target-latency-ms: 200                 # Desired executor response time
    latency-threshold: 0.2                 # Dead‑band fraction around target
    error-threshold: 0.05                  # Emergency brake trigger (error rate)
    window-size: 100                       # Sliding window of completions
    min-samples: 10                        # Minimum samples before adjusting
    emergency-factor: 0.5                  # Multiply RPS by this on emergency
    decrease-factor: 0.9                   # Multiply when latency too high
    increase-factor: 1.05                  # Multiply when latency low & errors low
    increase-error-threshold: 0.01         # Max error rate allowed for increase
    adjustment-interval-ms: 2000           # At most one RPS change per interval (0 = every completion)
    latency-ema-alpha: 0.7                 # EMA weight of the newest latency measurement (1 = no smoothing)
    direction-change-confirmations: 3      # Agreeing evaluations needed to reverse direction (1 = immediate)
```

**Stability controls (EQX-6).** The pre-EQX-6 controller adjusted on every completion. One latency spike
stays in the 100-sample window for 100 completions, so it was applied up to 100 times: ×0.9 per completion
until `min-rps`. At that rate, the sample window then took minutes to refresh. In simulation it averaged
2.2 rps against an ideal of 25. The three controls fix this:

- `adjustment-interval-ms` limits changes to one per interval. Each evaluation uses the mean latency of the
  completions since the previous one, so stale samples are never counted twice. This is the main fix.
- `direction-change-confirmations` is the dead-band dampener: a reversal needs that many consecutive
  agreeing evaluations. Returning to the dead band resets the count. The emergency brake (error rate above
  `error-threshold`) bypasses it but is still limited to one step per interval.
- `latency-ema-alpha` smooths the latency signal across evaluations. The smoothing time constant is about
  `adjustment-interval-ms / alpha`.

With the defaults, simulated over-throttling stays at or below 1.4% of the time, and load-induced overload
at or below 0.3%, across transient spikes, long spikes, capacity loss and a slow noisy executor. See
[mathematical invariants §25.7](src/mathematical-invariants3.md).

Ramp-up is time-based: each interval can raise the rate by `increase-factor`. From `initial-rps: 1`,
reaching 25 rps takes about 66 increases, roughly 2 minutes at a 2 s interval. Raise `initial-rps` or
`increase-factor` if cold starts must be faster. `alpha: 1.0`, `interval: 0` and `confirmations: 1`
reproduce the pre-EQX-6 controller exactly.

`penaltyFactor = 1000 / currentRps` - the value the priority calculator uses to weight in-flight
counts.

## Watchdog

```yaml
app:
  watchdog:
    interval-minutes: 5               # Reconciliation cadence
    drift-metric-max-keys: 100        # Keys exported as equalix.cms.estimation.drift{fairnessKey} per run
```

On every run, just before it rebuilds the CMS, the watchdog measures `drift_k = CMS estimate − in-flight
tasks` for every tracked key. Only keys with non-zero drift are exported per key, largest `|drift|` first
and capped at `drift-metric-max-keys`. This bounds Prometheus series cardinality. The aggregate gauges always
cover every key. See [operations](src/operations.md#cms-drift-eqx-5).

## Kafka (optional ingestion path)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    listener:
      ack-mode: manual_immediate
    consumer:
      group-id: equalix-ingestion
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer

app:
  kafka:
    topics:
      ingestion: equalix-tasks
```

Processing failures are **not** acknowledged, so Kafka redelivers the record.

## Actuator endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: never
  metrics:
    export:
      prometheus:
        enabled: true
```

`/actuator/health` and `/actuator/info` are unauthenticated (for probes). `/actuator/prometheus` requires `X-API-Key`.
