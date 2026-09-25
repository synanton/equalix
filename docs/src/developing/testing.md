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
- Scheduling (`app.scheduling.enabled: false`) and the Kafka listener are **off** in test YAML. Tests drive `PriorityCalculatorService` and `DispatcherService` directly, so jobs neither race `deleteAll` nor make results nondeterministic.

## Fairness experiment (EQX-1)

`ProportionalFairnessIntegrationTest` checks the weighted-fairness invariant end to end. It uses three backlogged tenants with weights 1/2/7 and runs 10,000 dispatches, with and without in-flight pressure. It asserts ϵmax(W) ≤ 2/|W| for W ∈ {10, 25, 100, 1,000, 10,000}. The measures (expected share, prefix and sliding-window error) are in `org.synanton.equalix.fairness.FairnessStatistics`, and the measured table is in [Mathematical invariants §6](../mathematical-invariants3.md). The test takes about 20 s per scenario. Run it again after any change to priority, dispatch or virtual time:

```bash
mvn test -Dtest=ProportionalFairnessIntegrationTest
```

## Aging simulation (EQX-4)

`AgingSimulationTest` is a domain-level simulation in simulated seconds, using the production `VirtualTimeService` and `AgingService`. For every aging policy, it compares long-wait promotion during a structural backlog with short-term weighted shares. `AgingIntegrationTest` checks the dispatcher's candidate pool against PostgreSQL.

## CMS error experiment (EQX-2)

- `CmsSignedUpdateErrorExperimentTest` measures the distribution of e_k over one watchdog window, for two sketch sizes and 1k/10k/50k keys, with and without injected accounting faults. It writes CSVs to `target/eqx-2/`.
- `CmsErrorPropagationTest` validates the §17 priority-error bound through `PriorityCalculatorService`.
- `CmsErrorRecorderIntegrationTest` checks the recorder and the Prometheus metrics end to end. It needs `@AutoConfigureObservability`, because Spring Boot disables metrics export in tests.

To regenerate the charts in `docs/src/images/eqx-2/` (requires matplotlib):

```bash
mvn test -Dtest=CmsSigntedUpdateErrorExperimentTes
python3 docs/samples/plot_cms_error.py
```

## Adaptive RPS stability simulation (EQX-6)

`AdaptiveRpsStabilitySimulationTest` drives the real `AdaptiveRpsController` with a `ManualClock` in closed loop against a modelled executor. It covers four workloads (transient spikes, long spikes, capacity loss, low-rate noisy) and seven parameter sets, and asserts no collapse, no overload and damped oscillation for the recommended defaults. Results are logged as `EQX-6 <workload> <configuration> -> ...`.

## Hierarchical fairness (EQX-7)

- `HierarchicalSelectorTest` checks the pure selection algorithm: sibling alternation, organization bound, weights, idle floors, promoted-first and quota, plus 10,000-dispatch simulations.
- `HierarchicalFairnessIntegrationTest` runs the EQX-1 harness in hierarchical mode against PostgreSQL. It checks that a hot department cannot starve its sibling, that an organization stays bounded against a single-leaf tenant, that a new sibling is served in the next tick, and that the Prometheus series appear.

Integration tests get an `AdjustableClock` (from `BaseIntegrationTest`). It is frozen at context start and reset before each test. Call `clock.advance(...)` to make tasks age.

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
