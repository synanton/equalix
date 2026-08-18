# Contributing to Equalix

## Workflow

1. Create a feature branch. Do not push directly to `main`; protect it with GitHub branch rules.
2. Open a pull request. CI runs `mvn -B package` on JDK 21.
3. Keep changes small and covered by tests.

## Tests

```bash
mvn test
```

Unit tests must not start a Spring context. Integration tests live under `src/test/java/org/synanton/equalix/integration` and use Testcontainers PostgreSQL.

## Code

Follow hexagonal packaging (`adapter.in` / `adapter.out` / `domain` / `config`) and the project Java rules. Defaults belong in `application.yml`, not in `@Value` fallbacks.

## Documentation

Documentation located in [docs](docs) folder. After API, config, or lifecycle changes:

```bash
cd docs && mdbook build
```

Runnable samples: `docs/samples/`. Design paper: `docs/design.md`.
