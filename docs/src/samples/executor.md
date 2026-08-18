# Echo executor

Equalix POSTs an **octet-stream** envelope to `{app.executor.base-url}/tasks/{taskId}/execute`:

```text
[16 bytes UUID][4 bytes BE payload length][payload][4 bytes BE prev length][previousResult]
```

`previousResult` is empty unless the task is sequential with `requiresPreviousResult`.

A 2xx response marks the task `COMMITTED`. The worker must then call:

```text
POST /api/v1/tasks/{taskId}/complete
X-API-Key: ...
{ "success": true, "result": "<base64>" }
```

## Run

```bash
python3 docs/samples/echo_executor.py
```

Listens on `9090` (or `EXECUTOR_PORT`). Completes back to `EQUALIX_URL` with `EQUALIX_API_KEY`. The result is the payload prefixed with `echo:`.

Source:

```python
{{#include ../../samples/echo_executor.py}}
```
