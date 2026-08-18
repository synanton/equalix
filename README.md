# Equalix

#### Equal chances, fair shares.

[![Java 21](https://img.shields.io/badge/java-21-orange)](https://adoptium.net/)
[![Spring Boot 3.x](https://img.shields.io/badge/spring--boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/build-maven-blue)](https://maven.apache.org/)
[![License Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Equalix** is an **eventually‑fair scheduler** for high‑throughput multi‑tenant systems.  
It uses virtual‑time scheduling and probabilistic accounting to maximise throughput while ensuring **long‑term proportional fairness** across fairness keys (tenants, clients, users).  
Think of it as the **fairness layer** between your task queue and your rate‑limited executor.

**Equalix** was purpose‑built to orchestrate asynchronous inference workloads in large‑scale LLM pools – environments where hundreds of tenants concurrently call shared AI models, each with distinct concurrency quotas, priority weights and latency SLAs. It ensures that a single tenant cannot starve others during traffic spikes, while its **Adaptive RPS controller** continuously monitors model response times and error rates to dynamically throttle load - maximizing GPU or API throughput without triggering provider rate limits, blowing through token budgets or overloading the underlying infrastructure.

---

## What it does (in 15 seconds)

```
Tenant A: 10 in-flight tasks ────────┐
                                     │
Tenant B: 1 in-flight task   ────────┼───►  Virtual-time scheduling
                                     │
Tenant C: 5 in-flight tasks  ────────┘

Each tenant's priority = now + (inFlightCount × penaltyFactor / weight)

A: now + 10 × 100ms = now + 1000ms  (pushed far into future)
B: now + 1 × 100ms  = now + 100ms   (runs first!)
C: now + 5 × 100ms  = now + 500ms   (runs between A and B)

Tenant B isn't starved by Tenant A's backlog.
Every tenant gets its proportional share over time.
```

Heavy tenants are **pushed back**, lighter tenants are **prioritised** – resulting in a continuously balanced, fair mix over time. No single tenant can dominate your shared resources.

---

## Why not Kafka, RabbitMQ or Temporal?

| Feature                        | Equalix | Kafka               | RabbitMQ         | Temporal |
| ------------------------------ | ------- | ------------------- | ---------------- | -------- |
| Message persistence            | ✅       | ✅                   | ✅                | ✅        |
| Fair scheduling                | ✅       | ❌ (partition‑based) | ❌ (plugin‑based) | ❌        |
| Weighted fairness              | ✅       | ❌                   | ❌                | ❌        |
| Adaptive RPS (backpressure)    | ✅       | ❌                   | ❌                | ❌        |
| Per‑tenant hard quotas         | ✅       | ❌                   | ❌                | ❌        |
| Virtual‑time scheduling        | ✅       | ❌                   | ❌                | ❌        |
| Durable workflow orchestration | ❌       | ❌                   | ❌                | ✅        |

**Equalix is not a general‑purpose broker.**  
It sits *in front of* a downstream executor and allocates its slots fairly. Use it when fairness and adaptability are your primary concerns.

---

**Use Equalix if:**

- You have **thousands of tenants, clients or users** producing tasks concurrently.
- You need **high throughput** and are willing to trade millisecond‑level exact fairness for **long‑term proportional fairness**.
- Your downstream executor is **rate‑limited** and benefits from adaptive throttling.
- You want **optional hard per‑tenant quotas** for SLA enforcement.
- You need **sequential execution** per fairness key (strict ordering).

**Don't use Equalix if:**

- You need **exact, millisecond‑level fairness** – a traditional weighted‑fair queue is a better fit.
- Total throughput is **very low** (< 50 RPS) – more precise algorithms have negligible overhead.
- You need **durable workflow orchestration** – use Temporal.
- You need a **general‑purpose stream broker** – use Kafka.

---

## Quick start

### Prerequisites
- Java 21
- Docker & Docker Compose (for PostgreSQL)
- Maven (or use the included Maven wrapper)

```bash
# Start PostgreSQL
docker compose up -d

# Run Equalix (override the API key in production)
export EQUALIX_API_KEY=changeme
./mvnw spring-boot:run
```

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



The dispatcher runs automatically. Point `app.executor.base-url` at your worker. Completions come back via `POST /api/v1/tasks/{id}/complete`.

------

## Key features

- **Virtual‑time scheduling** – `priority = now + (inFlightCount × penaltyFactor / weight)`. Heavy tenants are pushed back; higher weight gets a larger share.
- **Count‑Min Sketch (CMS)** – approximate in‑flight counts in O(1) time with fixed memory. Dispatch increments, completion decrements; Watchdog rebuilds from the task  table.
- **Adaptive RPS** – adjusts the penalty factor and per‑tick dispatch budget based on downstream latency and error rates. **Fully configurable** – see the configuration section below.
- **Per‑tenant hard quotas** – optional ceiling via durable `client_counts`.
- **Watchdog reconciliation** – repairs `client_counts` and CMS from `DISPATCHED`/`COMMITTED` rows every 5 minutes.
- **Anti‑starvation** – promotes long‑waiting tasks to the front.
- **Sequential execution** – one task at a time per fairness key, with block‑on‑failure and result passthrough.
- **API key auth** – `X-API-Key` on REST (and actuator beyond health/info).
- **Java 21 virtual threads** – high concurrency with low overhead.

------

## Architecture

The **Adaptive RPS Controller** feeds back into the Priority Calculator (via `penaltyFactor`) and the Dispatcher (via per‑tick budget cap). The **Watchdog** reconciles CMS and `client_counts` from the task table to handle crashes and missed callbacks.

------

## Core algorithm

On every dispatch cycle:

1. **Calculate priority** for each RECEIVED task:
   `priority = now + (estimatedInFlight(fairnessKey) × penaltyFactor / weight)`
2. **Select** QUEUED tasks with lowest priority, using `SELECT ... FOR UPDATE SKIP LOCKED`, capped by global concurrency and adaptive RPS.
3. **Update state**: `QUEUED → DISPATCHED`, CMS += 1, `client_counts` += 1.
4. **Dispatch** to remote executor (HTTP 2xx → `COMMITTED`).
5. **On completion or timeout**: CMS -= 1, `client_counts` -= 1, feed duration/success to Adaptive RPS.

------

## API

All `/api/v1/**` routes require header `X-API-Key`.

| Method | Endpoint                      | Description                                             |
| ------ | ----------------------------- | ------------------------------------------------------- |
| `POST` | `/api/v1/tasks`               | Ingest a task (with `fairnessKey`, `weight`, `payload`) |
| `GET`  | `/api/v1/tasks/{id}`          | Status & progress                                       |
| `GET`  | `/api/v1/tasks?fairnessKey=`  | List tasks for a fairness key                           |
| `POST` | `/api/v1/tasks/{id}/complete` | Mark task complete (success/fail)                       |
| `GET`  | `/api/v1/status`              | In‑flight estimate and current RPS                      |

**Full API documentation** with request/response examples: [API Reference](docs/api-reference.md).

------

## Configuration

Below is the **core configuration** block. All properties can be overridden via environment variables (e.g., `EQUALIX_ADAPTIVE_RPS_TARGET_LATENCY_MS`).

```yaml
app:
  security:
    api-key: ${EQUALIX_API_KEY:changeme}   # Required for all /api and /actuator/prometheus

  executor:
    base-url: http://localhost:9090        # Your worker endpoint
    connect-timeout-ms: 2000
    read-timeout-ms: 5000

  queue:
    max-tasks-in-process: 5000             # Global concurrency cap
    max-per-client-quota: 500              # Hard per‑key ceiling (0 = disabled)
    priority-calc-interval: 100            # ms - RECEIVED → QUEUED
    dispatcher-interval: 50                # ms - QUEUED → DISPATCHED
    worker-poll-size: 100                  # Batch size for priority calc
    max-queued-time-ms: 60000              # Anti‑starvation deadline
    task-timeout-ms: 300000                # In‑flight → TIMEOUT (0 = off)
    max-payload-bytes: 1048576

    cms:
      mode: local                          # 'local' or 'redis'
      width: 65536                         # ε = 2 / width
      depth: 5                             # δ = (1/2)^depth

    sequential:
      enabled: true
      client-block-timeout-ms: 60000
      dispatcher-interval: 50
      block-recovery-interval: 10000
      result-passthrough-interval: 60000

  # ---------- ADAPTIVE RPS ----------
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

  watchdog:
    interval-minutes: 5
```


### Adaptive RPS in detail

The controller maintains a sliding window of the last `window-size` completions. On each adjustment:

| Condition                                                    | Action                                                       |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| `errorRate > error-threshold`                                | `RPS = max(minRps, RPS × emergency-factor)` - emergency brake |
| `avgLatency > targetLatency × (1 + latency-threshold)`       | `RPS = max(minRps, RPS × decrease-factor)` – gradual decrease |
| `avgLatency < targetLatency × (1 - latency-threshold)` **and** `errorRate < increase-error-threshold` | `RPS = min(maxRps, RPS × increase-factor)` – gradual increase |

The **penalty factor** used in priority calculation is `1000 / currentRps`. When the downstream system slows, `currentRps` drops, the penalty factor grows, and heavy tenants are pushed further into the future – automatically reducing pressure.

**Full configuration guide:** [Configuration Reference](docs/configuration.md).

------

## Monitoring

Metrics are exposed at `/actuator/prometheus` (requires `X-API-Key`). Key metrics include:

- `equalix.task.duration` – task execution time (by success/failure)
- `equalix.task.errors` – error counter
- `equalix.adaptive.rps` – current adjusted RPS
- Standard JVM, process, and system metrics

Health (`/actuator/health`) and Info (`/actuator/info`) are public; all other actuator endpoints require the API key.

------

## Troubleshooting & FAQ

### Tasks stuck in `QUEUED`

- Check that the dispatcher is running and `app.queue.dispatcher-interval` is set.
- Verify that the remote executor is reachable (`app.executor.base-url`) – the dispatcher will not dispatch if the executor is down (it will log errors but continue).
- Ensure adaptive RPS is not clamping the dispatch budget to zero – check `currentRps` via `/api/v1/status`.

### Tasks remain in `DISPATCHED` or `COMMITTED` indefinitely

- The watchdog will eventually mark them `TIMEOUT` if `task-timeout-ms > 0`. Check the timeout value and increase if needed.
- Ensure completion webhook is correctly configured and reachable by the remote executor.

### CMS estimates drift

- The watchdog runs every `watchdog.interval-minutes` and reconciles CMS and `client_counts` from the task table. If drift is frequent, check for missed completions or crashes.

### Sequential mode not dispatching

- Check if the fairness key is blocked (`is_blocked=true` in `client_sequence_state`). The `ClientBlockRecoveryService` will auto‑unblock after `client-block-timeout-ms`.

------

## Sibling project: Resolutor

Equalix is designed to work with **Resolutor** – an execution planning engine for resource‑conflict resolution.

|                | **Equalix**                      | **Resolutor**                      |
| -------------- | -------------------------------- | ---------------------------------- |
| **Job**        | Fair scheduling                  | Execution planning                 |
| **Focus**      | *Which* task to run next         | *How* to run tasks safely          |
| **Key metric** | Fairness (throughput per tenant) | Parallelism (max safe concurrency) |

text

```
User/Batch Requests → Equalix (fairness) → Resolutor (planning) → Downstream Services
```

------

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.
Development: `./mvn clean verify` runs the full test suite (unit + integration).

------

## License

Apache 2.0 License – see [LICENSE](https://LICENSE).

------

*Equalix – Equal chances. Fair shares.*
