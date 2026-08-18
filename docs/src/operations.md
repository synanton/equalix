# Operations

## Multi-instance

Run 1–3 replicas on one Postgres. Safety comes from:

- ShedLock (JDBC) on every `@Scheduled` job
- Dispatcher `FOR UPDATE SKIP LOCKED`
- `@Version` on `TaskEntity`

For **strict** cross-instance fairness, set `app.queue.cms.mode=redis` so all nodes share one sketch. Local CMS plus Watchdog (5 minutes) is enough when brief over-dispatch is acceptable.

## Watchdog

Every `interval-minutes`, Equalix:

1. `COUNT(*)` of `DISPATCHED`/`COMMITTED` grouped by fairness key
2. Upserts `client_counts` to match (including zeros)
3. `cms.rebuild(snapshot)`

This is the recovery path after crashes and missed webhooks.

## Timeouts and blocks

- In-flight tasks older than `task-timeout-ms` → `TIMEOUT`, slot released. Sequential keys are **blocked**.
- Blocked sequential keys older than `client-block-timeout-ms` are unblocked; if the current task is still in-flight it is failed and the sequence advances.

## Metrics and logs

Scrape `/actuator/prometheus` with `X-API-Key`. Use `equalix.adaptive.rps` and `equalix.task.duration` for dashboards. Logs are `@Slf4j` at scheduler and adapter boundaries; there is no request correlation id yet.

## Kafka consumer

At-least-once: success and empty payloads are acked; exceptions are not. Duplicate ingest after a crash is possible (a second `RECEIVED` row). Idempotent producers are the caller’s problem.

## Boot version

Parent POM is Spring Boot **3.5.16** (final OSS 3.5 patch). 3.5 reached OSS EOL on 30 Jun 2026. Treat a move to 4.x as an operational project, not a drive-by bump.
