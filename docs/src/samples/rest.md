# REST ingest

Create a task, then print id and status.

```bash
./docs/samples/ingest.sh tenant-a "hello from equalix"
```

The script base64-encodes the second argument, POSTs `/api/v1/tasks`, and GETs the new id.

Equivalent curl:

```bash
PAYLOAD=$(printf 'hello' | base64)
curl -sS -X POST "${EQUALIX_URL:-http://localhost:8080}/api/v1/tasks" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: ${EQUALIX_API_KEY:-changeme}" \
  -d "{\"fairnessKey\":\"tenant-a\",\"weight\":1.0,\"payload\":\"${PAYLOAD}\"}"
```

List a key:

```bash
curl -sS -H "X-API-Key: ${EQUALIX_API_KEY:-changeme}" \
  "${EQUALIX_URL:-http://localhost:8080}/api/v1/tasks?fairnessKey=tenant-a"
```

If the echo executor is running, status should reach `SUCCEEDED`. If not, you will see `DISPATCHED` or later `TIMEOUT`.
