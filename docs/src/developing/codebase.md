# Codebase map

Root package: `org.synanton.equalix`.

```text
src/main/java/org/synanton/equalix/
├── EqualixApplication.java
├── domain/                 # no Spring Web / JPA in new code
│   ├── CreateTaskUseCase.java
│   ├── CompleteTaskUseCase.java
│   ├── GetTaskStatusUseCase.java
│   ├── TaskNotFoundException.java
│   ├── model/
│   ├── port/in/            # driving ports
│   ├── port/out/           # driven ports
│   └── service/            # scheduling, CMS accounting, RPS
├── adapter/
│   ├── in/rest/
│   ├── in/kafka/
│   ├── in/schedule/        # @Scheduled + ShedLock; call domain only
│   └── out/
│       ├── database/
│       ├── cms/
│       ├── executor/
│       └── metrics/
└── config/
    └── properties/         # @ConfigurationProperties, defaults in YAML
```

## Domain services (hot path)

| Class | Job |
|-------|-----|
| `PriorityCalculatorService` | `RECEIVED` → `QUEUED` + priority |
| `DispatcherService` | non-sequential dispatch |
| `SequentialDispatcherService` | one-at-a-time per key |
| `CompletionHandlerService` | non-sequential terminal |
| `SequentialCompletionHandlerService` | sequential terminal + block |
| `WatchdogService` | repair counts + CMS rebuild |
| `AdaptiveRpsController` | penalty factor and RPS |
| `TaskTimeoutService` | in-flight → `TIMEOUT` |
| `ClientBlockRecoveryService` | unblock sequential keys |
| `ResultPassthroughRecoveryService` | attach predecessor results |
| `DispatchAckService` | `DISPATCHED` → `COMMITTED` |

## Persistence

Flyway under `src/main/resources/db/migration/`:

- `V1` tasks + `client_counts`
- `V2` shedlock
- `V3` sequential columns + `client_sequence_state`

## Config beans

`AppConfig` enables `@ConfigurationProperties`. `CmsConfig` picks local vs Redis CMS. `SecurityConfig` + `ApiKeyAuthFilter` enforce `X-API-Key`.
