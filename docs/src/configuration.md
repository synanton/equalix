# Configuration

Defaults live in `src/main/resources/application.yml`. Do not put fallbacks in `@Value("${x:default}")`; inject resolved properties.

## Security and executor

```yaml
app:
  security:
    api-key: ${EQUALIX_API_KEY:changeme}
  executor:
    base-url: http://localhost:9090
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```

The worker must expose `POST {base-url}/tasks/{id}/execute` and call Equalix `/complete` when done.

## Scheduling

```yaml
app:
  scheduling:
    enabled: true                      # false: no scheduled jobs (tests, ingest-only nodes)
```

## Queue

```yaml
app:
  queue:
    max-tasks-in-process: 5000
    max-per-client-quota: 500          # 0 disables
    priority-calc-interval: 100        # ms
    dispatcher-interval: 50            # ms
    worker-poll-size: 100
    max-queued-time-ms: 60000
    task-timeout-ms: 300000            # 0 disables TIMEOUT
    max-payload-bytes: 1048576
    fairness-mode: flat                # flat | hierarchical, see app.hierarchical
    virtual-time:
      quantum: 1000                    # virtual-time units per task at weight 1.0
    aging:
      policy: none                     # none | linear | log | power
      lambda: 1000                     # aging rate, priority units
      gamma: 2.0                       # power exponent
      candidate-pool-size: 200         # rows per candidate ordering when aging is on
```

## Hierarchical fairness

```yaml
app:
  hierarchical:
    separator: /
    layers:
      - name: organization
        default-weight: 1.0
      - name: department
        default-weight: 1.0
    weights: {}                        # e.g. "[acme]": 2.0
    metrics-depth: 1
```

Used when `app.queue.fairness-mode` is `hierarchical`. See [Concepts](concepts.md#hierarchical-fairness).

## CMS

```yaml
app:
  queue:
    cms:
      mode: local                      # or redis
      width: 65536
      depth: 5
      redis:
        key-namespace: equalix:cms
        fallback-to-local: true
      error-sampling:
        enabled: false                 # load tests: publish equalix.cms.estimation.error*
        interval-ms: 1000
```

| Active keys | width | depth | Memory |
|-------------|-------|-------|--------|
| ≤ 10k | 65536 | 5 | ~2.6 MB |
| ≤ 100k | 131072 | 5 | ~5 MB |

## Sequential

```yaml
app:
  queue:
    sequential:
      enabled: true                    # false skips sequential schedulers
      client-block-timeout-ms: 60000
      dispatcher-interval: 50
      block-recovery-interval: 10000
      result-passthrough-interval: 60000
```

## Adaptive RPS and Watchdog

```yaml
app:
  adaptive-rps:
    enabled: true
    initial-rps: 1
    max-rps: 100
    target-latency-ms: 200
    error-threshold: 0.05
    adjustment-interval-ms: 2000       # at most one RPS change per interval
    latency-ema-alpha: 0.7             # latency smoothing (1 = off)
    direction-change-confirmations: 3  # dead-band dampener (1 = off)
  watchdog:
    interval-minutes: 5
    drift-metric-max-keys: 100         # per-key drift series exported per run
```

`penaltyFactor = 1000 / currentRps`. Dispatcher tick budget is `ceil(currentRps × dispatcher-interval / 1000)` when enabled.

## Kafka

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    listener:
      ack-mode: manual_immediate
app:
  kafka:
    topics:
      ingestion: equalix-tasks
```

Record **key** = fairness key (or `default`). **Value** = raw payload bytes (not JSON). Failed processing is **not** acked (redelivery). Empty values are acked and dropped.

REST-only: set `spring.kafka.listener.auto-startup: false` or provide a broker. The consumer bean is always registered.

The same defaults are listed in repository file `doc/configuration.md`.
