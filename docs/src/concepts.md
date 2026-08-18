# Concepts

## Fairness key

A string that names the group Equalix balances: tenant id, customer id, or project id. All scheduling and quotas are per key. The `client_counts` table still uses that historical name.

## Weight

A positive decimal (default `1.0`). The priority formula **divides** the in-flight penalty by weight, so `2.0` gets roughly twice the share of `1.0` at the same in-flight count.

## Virtual time (priority)

```text
priority = now_ms + (cms.estimateCount(key) × penaltyFactor / weight)
```

`penaltyFactor` is `1000 / currentRps` from the adaptive RPS controller. When the executor slows down, the penalty grows and busy keys yield more.

## In-flight

A task is in-flight in `DISPATCHED` or `COMMITTED`. CMS and `client_counts` increment on dispatch and decrement on terminal success, failure, or timeout.

## Count-Min Sketch (CMS)

Approximate per-key in-flight counts in fixed memory. Never used for hard quotas. `add(key, +1)` on dispatch, `add(key, -1)` on completion. Watchdog `rebuild`s from a SQL aggregate of in-flight rows.

- `app.queue.cms.mode=local` — in-process sketch (default).
- `app.queue.cms.mode=redis` — shared sketch for several instances.

## Hard quota

`app.queue.max-per-client-quota` (0 = off) is enforced in the dispatcher SQL against `client_counts`, not CMS.

## Adaptive RPS

A sliding window of the last 100 completions. High error rate halves `currentRps`; high latency multiplies by `0.9`; healthy low latency multiplies by `1.05` up to `max-rps`. The dispatcher also caps each tick by `ceil(currentRps × interval_seconds)` when adaptive RPS is enabled.

## Sequential mode

If `sequential=true` and `sequenceNumber` is set, Equalix runs **one task at a time** for that key, in sequence order. Other keys still run in parallel. Failure **blocks** the key until `ClientBlockRecoveryService` unblocks after `client-block-timeout-ms`.

## Anti-starvation

Queued non-sequential tasks older than `max-queued-time-ms` get `priority = 0` so they jump the queue.
