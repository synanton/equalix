# Concepts

## Fairness key

A string that names the group Equalix balances: tenant id, customer id, or project id. Quotas are per key. The `client_counts` table still uses that historical name.

In the default `flat` mode every key is an independent tenant. In `hierarchical` mode a key is a path such as `acme/sales`; see [Hierarchical fairness](#hierarchical-fairness).

## Weight

A positive decimal (default `1.0`). The priority formula **divides** the in-flight penalty by weight, so `2.0` gets roughly twice the share of `1.0` at the same in-flight count.

## Virtual time (priority)

Each fairness key has a persistent **virtual time** `T_k` stored in `client_virtual_time`. It records how much weighted service the key has already received and survives restarts and scheduling cycles.

When a task is queued it gets a **finish tag**:

```text
finish_tag = max(virtual_finish(key), V) + quantum / weight
priority   = round(finish_tag) + (cms.estimateCount(key) × penaltyFactor / weight)
```

- `virtual_finish(key)` is the tag of the key's previous queued task, so a key's tasks are spaced `quantum / weight` apart. A backlogged key with weight `w` receives about `w / Σw` of dispatches.
- `V` is the system virtual time (`scheduler_virtual_clock`), the highest tag dispatched so far. A key that was idle restarts at `V`, so idle periods do not bank credit for a later burst.
- On dispatch, `T_k` and `V` advance to the task's tag (`T_k ← T_k + quantum / w` for a backlogged key).
- `quantum` is `app.queue.virtual-time.quantum` (default `1000`).

`penaltyFactor` is `1000 / currentRps` from the adaptive RPS controller. When the executor slows down, the penalty grows and busy keys yield more. Ties are broken by `created_at`, then `id`.

## In-flight

A task is in-flight in `DISPATCHED` or `COMMITTED`. CMS and `client_counts` increment on dispatch and decrement on terminal success, failure, or timeout.

## Count-Min Sketch (CMS)

Approximate per-key in-flight counts in fixed memory. Never used for hard quotas. `add(key, +1)` on dispatch, `add(key, -1)` on completion. Watchdog `rebuild`s from a SQL aggregate of in-flight rows.

- `app.queue.cms.mode=local` — in-process sketch (default).
- `app.queue.cms.mode=redis` — shared sketch for several instances.

## Hard quota

`app.queue.max-per-client-quota` (0 = off) is enforced in the dispatcher SQL against `client_counts`, not CMS.

## Adaptive RPS

The controller adjusts `currentRps` at most once every `adjustment-interval-ms` (2 s by default):

- A high error rate halves it (emergency brake).
- High latency multiplies it by `0.9`.
- Healthy low latency multiplies it by `1.05`, up to `max-rps`.

Latency is the mean of the completions since the previous adjustment, smoothed by an EMA (`latency-ema-alpha`). Reversing direction needs `direction-change-confirmations` consecutive agreeing evaluations, which stops short spikes from flipping the rate back and forth. The emergency brake is never delayed. The dispatcher also caps each tick by `ceil(currentRps × interval_seconds)` when adaptive RPS is enabled.

## Sequential mode

If `sequential=true` and `sequenceNumber` is set, Equalix runs **one task at a time** for that key, in sequence order. Other keys still run in parallel. Failure **blocks** the key until `ClientBlockRecoveryService` unblocks after `client-block-timeout-ms`.

## Hierarchical fairness

With `app.queue.fairness-mode: hierarchical`, fairness keys are paths through a tenant tree. The layers come from `app.hierarchical.layers`, by default organization → department:

- `acme/sales` belongs to organization `acme/`;
- `smallclub` (one segment) competes directly with `acme/` at the top;
- segments beyond the last layer fold into it.

Every layer is fair among its backlogged siblings:

- `acme/` and `smallclub` split the system 50/50 (at equal weight), however many departments `acme` has;
- inside `acme`, `acme/sales` and `acme/it` split `acme`'s share, however large either backlog is.

The dispatcher selects tasks at dispatch time, walking down the tree and choosing the child with the least virtual runtime at each layer. Weights:

- a leaf uses its tasks' weight;
- other nodes use their layer's `default-weight`;
- `app.hierarchical.weights` overrides either, for example `"[acme]": 2.0`.

Keys must not contain empty segments (`acme//sales`, `acme/`). Aging is ignored in this mode; the `max-queued-time-ms` promotion still applies.

## Anti-starvation

Two mechanisms:

- **Aging** (`app.queue.aging.policy`, default `none`). The dispatcher ranks candidates by `priority − A(W)`, where W is the seconds a task has waited. `linear` = λ·W, `log` = λ·ln(1+W), `power` = λ·W^γ. Aging is evaluated at selection time because non-linear aging changes the order of tasks as time passes.
- **Promotion.** Queued non-sequential tasks older than `max-queued-time-ms` get `priority = 0` so they jump the queue. This hard backstop applies with every aging policy.

Under a steady backlog, aging does not change the long-term weighted shares. It only helps tasks stuck behind a structural backlog, such as a burst from a low-weight key (see [Mathematical invariants §10](mathematical-invariants3.md)).
