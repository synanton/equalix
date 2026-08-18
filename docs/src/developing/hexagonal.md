# Hexagonal architecture

Inbound adapters call **incoming ports**. Domain services call **outgoing ports**. Adapters implement outgoing ports.

```text
REST / Kafka / @Scheduled
        │
        ▼
  port.in  (TaskIngestionPort, TaskCompletionPort, TaskManagementPort)
        │
        ▼
     domain  (use cases + domain.service)
        │
        ▼
  port.out (TaskRepositoryPort, CMSProviderPort, RemoteExecutorPort, …)
        │
        ▼
  JPA / CMS / WebClient / Micrometer
```

## Rules (project Java conventions)

- Domain must not depend on `adapter` types. Prefer `TaskNotFoundException` over `jakarta.persistence.EntityNotFoundException`.
- Schedulers in `adapter.in.schedule` only invoke domain services; no SQL there.
- Repository adapters return **domain** `Task`, not JPA entities.
- `@Nullable` (`org.jspecify.annotations.Nullable`) on anything that may be null; unannotated references are non-null.
- `@RequiredArgsConstructor` for injection. Fluent domain models use `@Accessors(chain = true)`.
- Tunable defaults belong in `application.yml`, including test YAML.

## Adding an outgoing adapter

1. Extend or add a method on a `domain.port.out` interface.
2. Implement it under `adapter.out.*`.
3. Cover the adapter with a unit test (no Spring) and the domain service with mocks of the port.

## Adding an inbound API

1. DTO + validation in `adapter.in.rest.dto`.
2. Controller maps to a use case / incoming port.
3. Register the route in this book’s [REST API](../api.md) chapter.
