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
3. Measures CMS drift for every tracked key and publishes it (see below)
4. `cms.rebuild(snapshot)`

This is the recovery path after crashes and missed webhooks.

### CMS drift (EQX-5)

Before the rebuild, the watchdog compares the sketch with the task table. It uses the keys with in-flight tasks plus every `client_counts` row, so idle keys with phantom estimates are included. `drift_k = F̂_k − F_k` is the drift the scheduler was actually working with during the last interval.

| Metric | Meaning |
|--------|---------|
| `equalix_cms_estimation_drift{fairnessKey}` | Drift of one key. Only keys with non-zero drift, largest `\|drift\|` first, at most `app.watchdog.drift-metric-max-keys`. A key's series is **removed** when it stops drifting, so an absent series means 0. |
| `equalix_cms_estimation_drift_max` / `_min` | Largest overestimate, and largest underestimate as a negative number, over all keys |
| `equalix_cms_estimation_drift_absolute_total` | Sum of `\|drift\|` over all keys |
| `equalix_cms_estimation_drift_keys` / `_keys_sampled` | Keys with non-zero drift / keys compared |
| `equalix_cms_estimation_drift_timestamp_seconds` | When this instance last measured drift (0 before its first run) |

**What to expect.** With the default 65536×5 sketch and correct accounting, drift is 0 for every key (measured in EQX-2, [mathematical invariants §20](mathematical-invariants3.md)). Sustained non-zero drift is a signal, not noise:

- `_min < 0` (underestimation) is never produced by the sketch itself. It means an accounting fault: a CMS −1 without its DB change, e.g. a completion transaction that rolled back after updating the CMS and was then retried.
- `_max > 0` on keys with nothing in flight points to a phantom +1 (a dispatch transaction that rolled back), or to fairness keys whose `String.hashCode()` values collide. Collision drift survives the rebuild and shows up at every run for the same key pair.
- With a small sketch, some overestimation is normal. Use the measured p99 (for example 4 at 1024×3 with 5,000 tasks in flight) as the noise floor.

Example alert rules:

```yaml
- alert: EqualixCmsUnderestimation
  expr: max(equalix_cms_estimation_drift_min) < 0
  for: 15m          # three watchdog runs
- alert: EqualixCmsPersistentDrift
  expr: max(equalix_cms_estimation_drift_absolute_total) > 0
  for: 30m
- alert: EqualixCmsDriftStale
  expr: time() - max(equalix_cms_estimation_drift_timestamp_seconds) > 900
```

**Multiple instances.** The watchdog runs on whichever instance holds its ShedLock, and each instance exports only its own last measurement. With `cms.mode=local`, every instance has its own sketch, so the drift describes the sketch of the instance that ran the watchdog. Aggregate across instances with `max(...)`, and use `_timestamp_seconds` to find the freshest measurement. With `cms.mode=redis`, all instances share one sketch.

## Timeouts and blocks

- In-flight tasks older than `task-timeout-ms` → `TIMEOUT`, slot released. Sequential keys are **blocked**.
- Blocked sequential keys older than `client-block-timeout-ms` are unblocked; if the current task is still in-flight it is failed and the sequence advances.

## Metrics and logs

Scrape `/actuator/prometheus` with `X-API-Key`. Use `equalix.adaptive.rps` and `equalix.task.duration` for dashboards.

### CMS accuracy during load tests

Set `app.queue.cms.error-sampling.enabled=true` to sample e_k = CMS estimate − in-flight tasks for every tracked key every `interval-ms`. Metrics:

- `equalix_cms_estimation_error_count{direction="over|under|exact"}`: over- and underestimation frequencies.
- `equalix_cms_estimation_error_sum` / `_max` by direction: mean and worst error in each direction.
- `equalix_cms_estimation_error_magnitude{quantile="0.5|0.95|0.99"}`: percentiles of ∣e_k∣.

With the default 65536×5 sketch and exact accounting, e_k should be 0. Sustained non-zero error, especially `direction="under"`, points to an accounting fault (a rolled-back transaction after a CMS update) or to fairness keys whose `String.hashCode()` values collide. See [mathematical invariants §20](mathematical-invariants3.md). Logs are `@Slf4j` at scheduler and adapter boundaries; there is no request correlation id yet.

## Kafka consumer

At-least-once: success and empty payloads are acked; exceptions are not. Duplicate ingest after a crash is possible (a second `RECEIVED` row). Idempotent producers are the caller’s problem.

## Boot version

Parent POM is Spring Boot **3.5.16** (final OSS 3.5 patch). 3.5 reached OSS EOL on 30 Jun 2026. Treat a move to 4.x as an operational project, not a drive-by bump.
