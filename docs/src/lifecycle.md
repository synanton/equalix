# Task lifecycle

```text
RECEIVED → QUEUED → DISPATCHED → COMMITTED → SUCCEEDED
                                           → FAILED
                                           → TIMEOUT
```

| Status | Meaning |
|--------|---------|
| `RECEIVED` | Persisted; waiting for priority calculation |
| `QUEUED` | Priority assigned; eligible for dispatch |
| `DISPATCHED` | Slot taken; CMS and `client_counts` incremented; HTTP execute issued |
| `COMMITTED` | Executor returned HTTP 2xx; still waiting for `/complete` |
| `SUCCEEDED` | Terminal; slot released |
| `FAILED` | Terminal business or executor failure; slot released |
| `TIMEOUT` | In-flight longer than `task-timeout-ms`; slot released |

## Who moves the state

| Transition | Component |
|------------|-----------|
| → `RECEIVED` | `CreateTaskUseCase` (REST or Kafka) |
| `RECEIVED` → `QUEUED` | `PriorityCalculatorService` (~100 ms) |
| `QUEUED` → `DISPATCHED` | `DispatcherService` or `SequentialDispatcherService` (~50 ms) |
| `DISPATCHED` → `COMMITTED` | `DispatchAckService` on HTTP 2xx |
| in-flight → `SUCCEEDED`/`FAILED` | Completion webhook |
| in-flight → `TIMEOUT` | `TaskTimeoutService` |

ShedLock makes each scheduled job run on at most one instance. The dispatcher query uses `FOR UPDATE SKIP LOCKED` so multiple instances can still split work if the lock expires.

## Completion rules

- Completing a **terminal** task is a no-op (idempotent).
- Completing a task that is not in-flight (`RECEIVED`/`QUEUED`) is `400`.
- Sequential **failure** sets `client_sequence_state.is_blocked` and does not dispatch the next sequence number until recovery unblocks the key.
