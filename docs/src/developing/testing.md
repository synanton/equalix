# Testing

## Unit tests

- Name: `{Class}Test.java`
- No Spring context
- `@InjectMocks` / `@Mock` where it fits
- Method names start with `should`
- Assert **whole objects** with AssertJ (`isEqualTo`, `containsExactly`, …), not field-by-field
- Time: `Clock.fixed(...)`, `Instant.now(clock)` — never `Instant.now()` in production code under test

## Integration tests

Package: `org.synanton.equalix.integration`.

- `@SpringBootTest` + Testcontainers Postgres (`jdbc:tc:postgresql:16:///equalix`)
- Talk through HTTP (`MockMvc`) or inbound messages
- `@MockBean` / `@MockitoBean` only on `adapter.out` collaborators (remote executor)
- `deleteAll()` on repositories in `BaseIntegrationTest` only
- Scheduling and Kafka listener **off** in test YAML so jobs do not race `deleteAll`

## What to add when you change behaviour

| Change | Test |
|--------|------|
| Domain formula / state machine | Unit test on the service |
| SQL / Flyway | Integration test hitting the API |
| REST validation / errors | `@WebMvcTest` + API key header `test-api-key` |
| CMS math | `CountMinSketchAdapterTest` |

Run:

```bash
mvn test
```
