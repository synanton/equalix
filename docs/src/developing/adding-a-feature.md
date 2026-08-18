# Adding a feature

Worked example: a new metric `equalix.watchdog.corrections`.

1. **Decide the layer.** Counting Watchdog upserts is domain-adjacent observability. Emit from `WatchdogService` via `PerformanceMonitorPort` (extend the port) or a dedicated `WatchdogMetricsPort`. Do not inject `MeterRegistry` into `domain.service` if you can avoid it.

2. **Port first.** Add `void recordWatchdogCorrections(int updates);` on an outgoing port.

3. **Adapter.** Implement in `MicrometerPerformanceMonitorAdapter` with a `Counter` and **no** high-cardinality tags.

4. **Call site.** `WatchdogService.reconcile()` after the loop.

5. **Tests.** Unit-test the service with a mock port (`verify(monitor).recordWatchdogCorrections(1)`). Adapter test against `SimpleMeterRegistry`.

6. **Docs.** Mention the metric in [Operations](../operations.md) and [REST API](../api.md) actuator notes.

7. **PR.** Feature branch, `mvn test`, no force-push to `main`.

## Checklist for scheduling changes

- Job goes in `adapter.in.schedule` with `@SchedulerLock`
- Interval from YAML (`@Scheduled(fixedDelayString = "${...}")`)
- Multi-instance: SKIP LOCKED or a single ShedLock name
- Sequential-only jobs: `@ConditionalOnProperty(name = "app.queue.sequential.enabled", havingValue = "true")`
