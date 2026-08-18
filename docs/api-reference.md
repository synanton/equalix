# Equalix REST API Reference

Base path: `/api/v1`. All request and response bodies are JSON unless noted.

**Authentication:** every `/api/v1/**` request must include header `X-API-Key` matching
`app.security.api-key`. Missing or wrong keys return `401 Unauthorized`.

Errors follow a common envelope produced by `GlobalExceptionHandler`:

```json
{
  "code": "NOT_FOUND",
  "message": "Task not found: 550e8400-...",
  "timestamp": "2026-01-01T12:00:00Z",
  "fieldErrors": null
}
```

`fieldErrors` is populated only for `VALIDATION_FAILED` (400).

---

## Ingest a task

```
POST /api/v1/tasks
Content-Type: application/json
X-API-Key: <key>
```

Request body:

| Field                     | Type       | Required | Notes                                                     |
|---------------------------|------------|----------|-----------------------------------------------------------|
| `fairnessKey`             | string     | yes      | Non-blank                                                 |
| `weight`                  | number     | no       | Positive; default 1.0; higher weight gets a larger share  |
| `payload`                 | base64     | yes      | Opaque binary                                             |
| `sequential`              | boolean    | no       | Default false                                             |
| `sequenceNumber`          | integer    | no       | Required when `sequential=true`                           |
| `dependsOnTaskId`         | UUID       | no       | Predecessor task in sequence                              |
| `requiresPreviousResult`  | boolean    | no       | If true, predecessor's `result` is attached before dispatch |

Response:

- `201 Created` - body is the created task's UUID (JSON string).
- `400 Bad Request` - with `code: VALIDATION_FAILED` and `fieldErrors` when validation fails.

Example:

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "X-API-Key: changeme" \
  -d '{
    "fairnessKey": "tenant-123",
    "weight": 1.0,
    "payload": "aGVsbG8=",
    "sequential": false,
    "requiresPreviousResult": false
  }'
```

---

## Complete a task (remote executor callback)

```
POST /api/v1/tasks/{taskId}/complete
Content-Type: application/json
X-API-Key: <key>
```

Request body:

| Field     | Type    | Required | Notes                                                            |
|-----------|---------|----------|------------------------------------------------------------------|
| `success` | boolean | yes      |                                                                  |
| `result`  | base64  | no       | Present on success                                               |
| `error`   | string  | yes when `success=false` | Non-blank; required when `success=false`             |

Response:

- `200 OK` - task transitioned to `SUCCEEDED` or `FAILED`; CMS and counts decremented. Duplicate completions of an already-terminal task are ignored.
- `400 Bad Request` - validation failure (e.g., `success=false` with no `error`).
- `404 Not Found` - no task with the given ID.

Example:

```bash
curl -X POST http://localhost:8080/api/v1/tasks/550e8400-e29b-41d4-a716-446655440000/complete \
  -H "Content-Type: application/json" \
  -H "X-API-Key: changeme" \
  -d '{"success": true, "result": "b3V0cHV0"}'
```

---

## Get task status

```
GET /api/v1/tasks/{taskId}
```

Response (200):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "fairnessKey": "tenant-123",
  "status": "QUEUED",
  "priority": 1751376000123,
  "createdAt": "2026-01-01T00:00:00Z",
  "completedAt": null,
  "retryCount": 0,
  "lastError": null
}
```

`status` is one of `RECEIVED`, `QUEUED`, `DISPATCHED`, `COMMITTED`, `SUCCEEDED`, `FAILED`, `TIMEOUT`.

`404 Not Found` when no task exists.

---

## List tasks by fairness key

```
GET /api/v1/tasks?fairnessKey=<key>[&status=<STATUS>]
```

Query parameters:

| Param         | Required | Notes                                                             |
|---------------|----------|-------------------------------------------------------------------|
| `fairnessKey` | yes      |                                                                   |
| `status`      | no       | Filter to a single status; omit to return all statuses for the key |

Response: `200 OK` with an array of the task-status object shown above.

---

## System status

```
GET /api/v1/status
X-API-Key: <key>
```

Response (200):

```json
{
  "inFlight": 12,
  "currentRps": 8.5
}
```

`inFlight` is the CMS total estimate. `currentRps` is the adaptive controller's current cap.

