# REST API

Base path: `/api/v1`. JSON unless noted. Every route under `/api/v1` requires header **`X-API-Key`**.

## Errors

```json
{
  "code": "NOT_FOUND",
  "message": "Task not found: 550e8400-...",
  "timestamp": "2026-01-01T12:00:00Z",
  "fieldErrors": null
}
```

| HTTP | `code` |
|------|--------|
| 400 | `BAD_REQUEST`, `VALIDATION_FAILED` |
| 401 | missing/wrong API key |
| 404 | `NOT_FOUND` |
| 500 | `INTERNAL_ERROR` |

## Ingest

`POST /api/v1/tasks`

| Field | Required | Notes |
|-------|----------|-------|
| `fairnessKey` | yes | Non-blank |
| `weight` | no | Positive; default `1.0` |
| `payload` | yes | Base64; max `app.queue.max-payload-bytes` (1 MiB default) |
| `sequential` | no | Default `false` |
| `sequenceNumber` | if sequential | Required when `sequential` is true |
| `dependsOnTaskId` | no | Predecessor UUID |
| `requiresPreviousResult` | no | Attach predecessor `result` before dispatch |

`201 Created` — body is the task UUID (JSON string).

## Status

`GET /api/v1/tasks/{taskId}` — one task.

`GET /api/v1/tasks?fairnessKey=<key>&status=<STATUS>` — list; `status` optional.

## Complete (executor callback)

`POST /api/v1/tasks/{taskId}/complete`

```json
{ "success": true, "result": "b3V0cHV0" }
```

On failure, `error` is required and non-blank:

```json
{ "success": false, "error": "downstream 500" }
```

## System snapshot

`GET /api/v1/status`

```json
{ "inFlight": 12, "currentRps": 8.5 }
```

`inFlight` is the CMS total estimate, not an exact SQL count.

## Actuator

| Path | Auth |
|------|------|
| `/actuator/health`, `/actuator/info` | none (probes) |
| `/actuator/prometheus` | `X-API-Key` |

Metrics: `equalix.task.duration` (tag `success`), `equalix.task.errors`, `equalix.adaptive.rps`. Fairness keys are **not** used as Prometheus labels (cardinality).
