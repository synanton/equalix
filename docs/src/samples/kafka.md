# Kafka ingest

The consumer reads topic `app.kafka.topics.ingestion` (default `equalix-tasks`).

- **Key** — fairness key (`null` becomes `default`)
- **Value** — raw bytes (the task payload, not JSON)
- **Weight** — always `1.0` on this path
- **Sequential** — always `false` on this path

Use REST if you need weight or sequential metadata.

## Produce

Requires a broker at `localhost:9092` and `kafka-python` or the `kafka-console-producer`.

```bash
pip install kafka-python
python3 docs/samples/kafka_produce.py tenant-kafka "payload-bytes"
```

Failed ingest is not acknowledged, so the offset does not advance until `createTask` succeeds. Empty values are acked and dropped.

REST-only developers should set `spring.kafka.listener.auto-startup: false` so the app does not hammer a missing broker.
