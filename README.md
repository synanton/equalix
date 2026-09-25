# Equalix

#### Equal chances, fair shares.

[![Java 21](https://img.shields.io/badge/java-21-orange)](https://adoptium.net/)
[![Spring Boot 3.x](https://img.shields.io/badge/spring--boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/build-maven-blue)](https://maven.apache.org/)
[![License Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Equalix** is an **eventually-fair weighted-fair scheduler** for high-throughput multi-tenant systems.
It uses persistent virtual-time scheduling and probabilistic accounting to maximise throughput while giving every fairness key (tenant, client, user) its **weighted share** of a shared executor. In a measured run with continuously backlogged tenants, no tenant was ever more than two tasks off its weighted share, in any window.
Think of it as the **fairness layer** between your task queue and your rate-limited executor.

**Equalix** was purpose-built to orchestrate asynchronous inference workloads in large-scale LLM pools. In these environments, hundreds of tenants call shared AI models concurrently, each with its own concurrency quota, priority weight and latency SLA. Equalix ensures that a single tenant cannot starve the others during traffic spikes. Its **Adaptive RPS controller** monitors model response times and error rates and throttles load dynamically. That maximises GPU or API throughput without triggering provider rate limits, blowing through token budgets or overloading the underlying infrastructure.

---

## What it does (in 15 seconds)

```
Tenant A (weight 1): 400 queued ──┐
                                  │
Tenant B (weight 2):  40 queued ──┼───►  Virtual-time scheduling  ───►  executor
                                  │
Tenant C (weight 7):  40 queued ──┘

Each task gets a finish tag in its tenant's persistent virtual time:
  tag = max(tenant's last tag, system virtual time) + quantum / weight

A: 1000, 2000, 3000, ...      (weight 1: tags far apart)
B:  500, 1000, 1500, ...
C:  143,  286,  429, ...      (weight 7: tags close together)

Dispatch in tag order  →  A : B : C = 10% : 20% : 70% of the executor,
no matter how deep A's backlog is.
```

Every tenant progresses through its share at a rate proportional to its weight. A tenant that was idle restarts at the current system virtual time, so it cannot bank credit and burst later. An in-flight pressure term also pushes back tenants that already hold many executor slots.

Multi-department customers can be scheduled **hierarchically**, fair at every layer. An organization with 50 departments gets the same share as a single-key tenant of equal weight, and inside it a hot department cannot starve its siblings.

---

## Why not Kafka, RabbitMQ or Temporal?

| Feature                        | Equalix | Kafka               | RabbitMQ         | Temporal |
| ------------------------------ | ------- | ------------------- | ---------------- | -------- |
| Message persistence            | ✅       | ✅                   | ✅                | ✅        |
| Fair scheduling                | ✅       | ❌ (partition-based) | ❌ (plugin-based) | ❌        |
| Weighted fairness              | ✅       | ❌                   | ❌                | ❌        |
| Hierarchical (org → dept) fairness | ✅   | ❌                   | ❌                | ❌        |
| Adaptive RPS (backpressure)    | ✅       | ❌                   | ❌                | ❌        |
| Per-tenant hard quotas         | ✅       | ❌                   | ❌                | ❌        |
| Virtual-time scheduling        | ✅       | ❌                   | ❌                | ❌        |
| Durable workflow orchestration | ❌       | ❌                   | ❌                | ✅        |

**Equalix is not a general-purpose broker.**
It sits *in front of* a downstream executor and allocates its slots fairly. Use it when fairness and adaptability are your primary concerns.

---

**Use Equalix if:**

- You have **thousands of tenants, clients or users** producing tasks concurrently.
- You need **weighted, measurable fairness** at **high throughput**.
- Your customers are **organizations with departments or users**, and fairness must hold at every layer.
- Your downstream executor is **rate-limited** and benefits from adaptive throttling.
- You want **optional hard per-tenant quotas** for SLA enforcement.
- You need **sequential execution** per fairness key (strict ordering).

**Don't use Equalix if:**

- Total throughput is **very low** (< 50 RPS). A simple in-process fair queue has less moving parts.
- You need **durable workflow orchestration**. Use Temporal.
- You need a **general-purpose stream broker**. Use Kafka.

---

## Quick start

### Prerequisites
- Java 21
- Maven 3.8+
- Docker & Docker Compose (for PostgreSQL)

```bash
# Start PostgreSQL
docker compose up -d

# Run Equalix (override the API key in production)
export EQUALIX_API_KEY=changeme
mvn spring-boot:run
```

Kafka ingestion is optional. Without a broker on `localhost:9092`, the Kafka listener logs connection warnings and REST ingestion works as usual.

### Ingest a task

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "X-API-Key: changeme" \
  -d '{
    "fairnessKey": "tenant-123",
    "weight": 1.0,
    "payload": "aGVsbG8="
  }'
```

**Response:** `201 Created` with the task UUID.

### Check task status

```bash
curl -H "X-API-Key: changeme" http://localhost:8080/api/v1/tasks/{taskId}
```

**Response:** JSON with current status, priority, timestamps, etc.

### Get system snapshot

```bash
curl -H "X-API-Key: changeme" http://localhost:8080/api/v1/status
```

**Response:**

```json
{"inFlight": 12, "currentRps": 8.5}
```

The dispatcher runs automatically (`app.scheduling.enabled: true`). Point `app.executor.base-url` at your worker. Completions come back via `POST /api/v1/tasks/{id}/complete`.

------

## Key features

- **Persistent virtual-time scheduling.** Each fairness key keeps its virtual time in PostgreSQL. Tags advance by `quantum / weight`, so weighted shares survive restarts and bursts, and idle keys cannot bank credit.
- **Hierarchical fairness** (`fairness-mode: hierarchical`). Keys are paths such as `acme/sales`, and every layer is fair among its siblings, in the style of CFS group scheduling. Weights can be overridden per organization or department.
- **Count-Min Sketch (CMS).** Approximate in-flight counts in O(1) time with fixed memory, local or shared via Redis. Updates apply only after the database transaction commits. The Watchdog rebuilds the sketch from the task table, and so does startup.
- **Adaptive RPS.** Global capacity control from downstream latency and error rate, with an adjustment interval, latency EMA and a dead-band dampener, so latency spikes don't collapse throughput.
- **Per-tenant hard quotas.** Optional ceiling via the durable `client_counts` table.
- **Anti-starvation.** Configurable aging (`linear`, `log`, `power`) plus a hard `max-queued-time-ms` promotion.
- **Watchdog reconciliation.** Repairs `client_counts` and the CMS from `DISPATCHED`/`COMMITTED` rows every 5 minutes, and publishes CMS drift per key and per layer.
- **Sequential execution.** One task at a time per fairness key, with block-on-failure and result passthrough.
- **API key auth.** `X-API-Key` on REST, and on actuator endpoints beyond health/info.

------

## Architecture

The **Priority Calculator** assigns each task its virtual-time tag plus in-flight pressure. The **Dispatcher** selects QUEUED tasks with `SELECT … FOR UPDATE SKIP LOCKED`:
- by priority in flat mode;
- by descending the tenant tree in hierarchical mode.

The **Adaptive RPS Controller** feeds back into both: into the Priority Calculator via `penaltyFactor`, and into the Dispatcher via a per-tick budget cap. The **Watchdog** reconciles the CMS and `client_counts` from the task table to handle crashes and missed callbacks.

Design paper: [docs/design.md](docs/design.md). The mathematical model with measured bounds is in [Mathematical invariants](docs/src/mathematical-invariants3.md).

------

## Core algorithm

On every cycle:

1. **Tag** each RECEIVED task in its key's persistent virtual time:
   `tag = max(virtualFinish(key), V) + quantum / weight`
   `priority = tag + estimatedInFlight(key) × penaltyFactor / weight`
2. **Select** QUEUED tasks, capped by global concurrency and the adaptive RPS budget:
   - **flat mode:** lowest priority first;
   - **hierarchical mode:** at each layer, the child with the least virtual runtime.
3. **Update state**: `QUEUED → DISPATCHED`, `client_counts += 1`, and the key's virtual time and system virtual time `V` advance. The CMS gets `+1` once the transaction commits.
4. **Dispatch** to the remote executor (HTTP 2xx → `COMMITTED`).
5. **On completion or timeout**: `client_counts −= 1`, CMS `−1` after commit, and the duration/success are fed to Adaptive RPS.

Measured fairness, with tenants at weights 1 : 2 : 7 continuously backlogged over 10,000 dispatches against PostgreSQL:
- exact shares of 10% / 20% / 70%;
- a worst case of 2 tasks off the weighted share in any window.

------

## API

All `/api/v1/**` routes require header `X-API-Key`.

| Method | Endpoint                      | Description                                             |
| ------ | ----------------------------- | ------------------------------------------------------- |
| `POST` | `/api/v1/tasks`               | Ingest a task (with `fairnessKey`, `weight`, `payload`) |
| `GET`  | `/api/v1/tasks/{id}`          | Status & progress                                       |
| `GET`  | `/api/v1/tasks?fairnessKey=`  | List tasks for a fairness key (optional `status`)       |
| `POST` | `/api/v1/tasks/{id}/complete` | Mark task complete (success/fail)                       |
| `GET`  | `/api/v1/status`              | In-flight estimate and current RPS                      |

**Full API documentation** with request/response examples: [API Reference](docs/api-reference.md).

------

## Configuration

Below is the **core configuration** block. Any property can be overridden with an environment variable using Spring's relaxed binding, e.g. `APP_ADAPTIVE_RPS_TARGET_LATENCY_MS=300` for `app.adaptive-rps.target-latency-ms`.

```yaml
app:
  scheduling:
    enabled: true                          # Scheduled jobs (dispatchers, watchdog, recovery)

  security:
    api-key: ${EQUALIX_API_KEY:changeme}   # Required for all /api and /actuator/prometheus

  executor:
    base-url: http://localhost:9090        # Your worker endpoint
    connect-timeout-ms: 2000
    read-timeout-ms: 5000

  queue:
    max-tasks-in-process: 5000             # Global concurrency cap
    max-per-client-quota: 500              # Hard per-key ceiling (0 = disabled)
    priority-calc-interval: 100            # ms - RECEIVED → QUEUED
    dispatcher-interval: 50                # ms - QUEUED → DISPATCHED
    worker-poll-size: 100                  # Batch size for priority calc
    max-queued-time-ms: 60000              # Anti-starvation deadline (hard promotion)
    task-timeout-ms: 300000                # In-flight → TIMEOUT (0 = off)
    max-payload-bytes: 1048576
    fairness-mode: flat                    # flat | hierarchical

    virtual-time:
      quantum: 1000                        # Virtual-time units per task at weight 1.0

    aging:
      policy: none                         # none | linear | log | power
      lambda: 1000
      gamma: 2.0
      candidate-pool-size: 200

    cms:
      mode: local                          # 'local' or 'redis'
      width: 65536                         # ε = 2 / width
      depth: 5                             # δ = (1/2)^depth
      redis:
        key-namespace: equalix:cms         # Redis keys: {namespace}:v2, {namespace}:v2:total
        fallback-to-local: true
      error-sampling:
        enabled: false                     # Load tests: publish equalix.cms.estimation.error*
        interval-ms: 1000

    sequential:
      enabled: true
      client-block-timeout-ms: 60000
      dispatcher-interval: 50
      block-recovery-interval: 10000
      result-passthrough-interval: 60000

  hierarchical:                            # Used when fairness-mode is hierarchical
    separator: /
    layers:
      - name: organization
        default-weight: 1.0
      - name: department
        default-weight: 1.0
    weights: {}                            # e.g. "[acme]": 2.0, "[acme/sales]": 3.0
    metrics-depth: 1

  # ---------- ADAPTIVE RPS ----------
  adaptive-rps:
    enabled: true
    initial-rps: 1                         # Starting cap; ramps up
    min-rps: 1                             # Absolute floor (never go below)
    max-rps: 100                           # Absolute ceiling
    target-latency-ms: 200                 # Desired executor response time
    latency-threshold: 0.2                 # Dead-band fraction around target
    error-threshold: 0.05                  # Emergency brake trigger (error rate)
    window-size: 100                       # Sliding window of completions
    min-samples: 10                        # Minimum samples before adjusting
    emergency-factor: 0.5                  # Multiply RPS by this on emergency
    decrease-factor: 0.9                   # Multiply when latency too high
    increase-factor: 1.05                  # Multiply when latency low & errors low
    increase-error-threshold: 0.01         # Max error rate allowed for increase
    adjustment-interval-ms: 2000           # At most one RPS change per interval
    latency-ema-alpha: 0.7                 # Latency smoothing (1 = off)
    direction-change-confirmations: 3      # Dead-band dampener (1 = off)

  watchdog:
    interval-minutes: 5
    drift-metric-max-keys: 100             # Per-key drift series exported per run
```

### Adaptive RPS in detail

The controller evaluates at most once per `adjustment-interval-ms`. It uses the mean latency of the completions since the previous evaluation, smoothed by an EMA, and the error rate over the last `window-size` completions:

| Condition                                                    | Action                                                       |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| `errorRate > error-threshold`                                | `RPS = max(minRps, RPS × emergency-factor)`: emergency brake, never dampened |
| `latency > targetLatency × (1 + latency-threshold)`          | `RPS = max(minRps, RPS × decrease-factor)`: gradual decrease |
| `latency < targetLatency × (1 - latency-threshold)` **and** `errorRate < increase-error-threshold` | `RPS = min(maxRps, RPS × increase-factor)`: gradual increase |

Reversing direction requires `direction-change-confirmations` consecutive agreeing evaluations, so short spikes don't flip the rate back and forth.

In closed-loop simulation, the original per-completion controller collapsed to `min-rps` after a single latency spike: 2.2 rps against an ideal of 25. The defaults keep over-throttling at or below 1.4% of the time, and overload at or below 0.3%. Ramp-up is time-based: +5% per interval, so from `initial-rps: 1` it takes about 2 minutes to reach 25 rps.

The **penalty factor** used in priority calculation is `1000 / currentRps`. When the downstream system slows, `currentRps` drops, the penalty factor grows, and tenants holding many in-flight tasks are pushed further back, automatically reducing pressure.

**Full configuration guide:** [Configuration Reference](docs/configuration.md).

------

## Monitoring

Metrics are exposed at `/actuator/prometheus` (requires `X-API-Key`). Key metrics include:

- `equalix_task_duration_seconds`: task execution time, by success/failure
- `equalix_task_errors_total`: error counter
- `equalix_adaptive_rps`: current adjusted RPS
- `equalix_cms_estimation_drift{fairnessKey,layer}`, plus `_max`, `_min`, `_absolute`, `_keys` and `_timestamp_seconds`: CMS drift measured by the Watchdog before each rebuild. The expected value is 0.
- `equalix_hierarchy_dispatches_total{layer,node}`: dispatches per organization in hierarchical mode, for isolation dashboards
- `equalix_cms_estimation_error*`: CMS error distribution, when `cms.error-sampling` is enabled
- Standard JVM, process, and system metrics

Health (`/actuator/health`) and Info (`/actuator/info`) are public; all other actuator endpoints require the API key. Example alert rules are in [Operations](docs/src/operations.md).

------

## Troubleshooting & FAQ

### Tasks stuck in `QUEUED`

- Check that scheduling is enabled (`app.scheduling.enabled: true`) and that `app.queue.dispatcher-interval` is set.
- Ensure adaptive RPS is not clamping the dispatch budget. Check `currentRps` via `/api/v1/status`. After a restart, the rate ramps up from `initial-rps` by `increase-factor` per `adjustment-interval-ms`.
- Check the per-tenant quota (`max-per-client-quota`) and the global cap (`max-tasks-in-process`) against in-flight tasks that never completed.

### Tasks remain in `DISPATCHED` or `COMMITTED` indefinitely

- If the executor was unreachable, sending fails and the task stays `DISPATCHED`. `TaskTimeoutService` marks in-flight tasks `TIMEOUT` after `task-timeout-ms` (when > 0) and releases their slots.
- Ensure the completion webhook is correctly configured and reachable by the remote executor.

### CMS estimates drift

- Watch `equalix_cms_estimation_drift_*`. With the default sketch it should be 0. Underestimation (`_min < 0`) or persistent drift points to lost updates, such as a crash between a commit and the sketch update. The Watchdog repairs it every `watchdog.interval-minutes`.

### Sequential mode not dispatching

- Check if the fairness key is blocked (`is_blocked=true` in `client_sequence_state`). The `ClientBlockRecoveryService` will auto-unblock after `client-block-timeout-ms`.

------

## Sibling project: Resolutor

Equalix is designed to work with **Resolutor**, an execution planning engine for resource-conflict resolution.

|                | **Equalix**                      | **Resolutor**                      |
| -------------- | -------------------------------- | ---------------------------------- |
| **Job**        | Fair scheduling                  | Execution planning                 |
| **Focus**      | *Which* task to run next         | *How* to run tasks safely          |
| **Key metric** | Fairness (throughput per tenant) | Parallelism (max safe concurrency) |

```
User/Batch Requests → Equalix (fairness) → Resolutor (planning) → Downstream Services
```

------

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
Development: `mvn clean verify` runs the full test suite, unit and integration. Integration tests need Docker for Testcontainers.

------

## License

Apache 2.0 License. See [LICENSE](LICENSE).

------

*Equalix: Equal chances. Fair shares.*
