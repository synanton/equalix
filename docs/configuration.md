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
```

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
        key-namespace: equalix:cms
        fallback-to-local: true       # Fall back to in-memory sketch if Redis is down
```

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
```

`penaltyFactor = 1000 / currentRps` - the value the priority calculator uses to weight in-flight
counts.

## Watchdog

```yaml
app:
  watchdog:
    interval-minutes: 5               # Reconciliation cadence
```

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
