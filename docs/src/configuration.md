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
```

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
  watchdog:
    interval-minutes: 5
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
