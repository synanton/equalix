# Getting started

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker (for PostgreSQL)
- Optional: [mdBook](https://rust-lang.github.io/mdBook/) to render this book

## Start Postgres

From the repository root:

```bash
docker compose up -d
```

This starts PostgreSQL 16 on `localhost:5432` with database/user/password `equalix`.

## Run Equalix

```bash
export EQUALIX_API_KEY=changeme
mvn spring-boot:run
```

Flyway applies migrations on startup. Health (no API key):

```bash
curl -s http://localhost:8080/actuator/health
```

## First task

Payloads are JSON **base64**. `aGVsbG8=` is `hello`.

```bash
curl -sS -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "X-API-Key: changeme" \
  -d '{
    "fairnessKey": "tenant-a",
    "weight": 1.0,
    "payload": "aGVsbG8="
  }'
```

The body of a `201` response is a UUID string. Poll it:

```bash
curl -sS -H "X-API-Key: changeme" \
  http://localhost:8080/api/v1/tasks/<task-id>
```

Without a worker at `http://localhost:9090`, dispatch still moves the task to `DISPATCHED`. HTTP errors are logged; after `app.queue.task-timeout-ms` the task becomes `TIMEOUT`. Run the [echo executor](samples/executor.md) so tasks complete.

## Environment overrides

| Variable | Default (local) | Purpose |
|----------|-----------------|--------|
| `EQUALIX_API_KEY` | `changeme` | `X-API-Key` for `/api/v1` and Prometheus |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/equalix` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `equalix` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | `equalix` | DB password |

Production must set a strong API key and real credentials. Do not ship `changeme`.

## Tests

```bash
mvn test
```

Integration tests use Testcontainers PostgreSQL. Scheduling and the Kafka listener are disabled in `src/test/resources/application.yml`.
