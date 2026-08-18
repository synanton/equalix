# Sequential pipeline

One fairness key, three tasks, `sequenceNumber` 1..3. Task 2 asks for the predecessor result.

```bash
python3 docs/samples/echo_executor.py &
./docs/samples/sequential.sh pipeline-demo
```

The script:

1. Creates seq 1 (no dependency).
2. Creates seq 2 with `dependsOnTaskId` = task 1 and `requiresPreviousResult: true`.
3. Creates seq 3 depending on task 2.

Equalix will not dispatch seq 2 until seq 1 has completed and the passthrough recovery (or sequential dispatcher) attached `previousResult`. A failure on seq 1 **blocks** the key until `client-block-timeout-ms`.

Poll:

```bash
curl -sS -H "X-API-Key: ${EQUALIX_API_KEY:-changeme}" \
  "${EQUALIX_URL:-http://localhost:8080}/api/v1/tasks?fairnessKey=pipeline-demo"
```
