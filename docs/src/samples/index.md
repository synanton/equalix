# Sample overview

Runnable files live in `docs/samples/` (next to this book, not inside `src/`).

| File | Role |
|------|------|
| `echo_executor.py` | Worker: accepts Equalix execute envelopes, calls `/complete` |
| `lib.sh` | Shared URL, API key, helpers |
| `ingest.sh` | POST one non-sequential task |
| `status.sh` | GET task + `/api/v1/status` |
| `complete.sh` | Manual complete (if you are not using the executor) |
| `sequential.sh` | Three ordered tasks for one key |
| `kafka_produce.py` | Produce a raw payload to `equalix-tasks` |

Typical local loop:

```bash
docker compose up -d
export EQUALIX_API_KEY=changeme
mvn spring-boot:run          # terminal 1

python3 docs/samples/echo_executor.py   # terminal 2

./docs/samples/ingest.sh tenant-a hello
```

Scripts assume Equalix at `http://localhost:8080` and the executor at `http://localhost:9090`. Override with `EQUALIX_URL`, `EQUALIX_API_KEY`, `EXECUTOR_PORT`.
