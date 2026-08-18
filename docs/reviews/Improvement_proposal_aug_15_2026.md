## Project Improvement Proposal: Equalix

### 1. Executive Summary

Equalix is a well-architected, production-ready fairness scheduler for  high-throughput multi-tenant systems, purpose-built for orchestrating  asynchronous inference workloads in large-scale LLM pools. The project  demonstrates strong engineering discipline with hexagonal architecture,  comprehensive documentation, and a modern Java 21 + Spring Boot 3.x  stack.

This proposal outlines actionable improvements across **code quality**, **testing & observability**, **performance & scalability**, **developer experience**, and **community building** – elevating Equalix from excellent to outstanding.

------

### 2. Current Strengths (To Preserve)

| Area              | Strength                                                     |
| ----------------- | ------------------------------------------------------------ |
| **Architecture**  | Hexagonal (ports & adapters) with clear domain/infrastructure separation |
| **Documentation** | Exceptional – README, 20‑section design doc, API reference, configuration guide, mdBook |
| **Configuration** | Fully externalised via `AdaptiveRpsProperties` with comprehensive validation |
| **Thread Safety** | `ReentrantReadWriteLock` + `AtomicReference` ensure safe concurrent access |
| **Modern Stack**  | Java 21 virtual threads, Spring Boot 3.x, Testcontainers     |
| **Scheduling**    | ShedLock for distributed job coordination across multiple instances |

------

### 3. Areas for Improvement

Based on the code review, the following areas present opportunities for enhancement:

#### 3.1 Code Quality

| Issue                                   | Location                                          | Impact                                                       |
| --------------------------------------- | ------------------------------------------------- | ------------------------------------------------------------ |
| **Logging inside write-lock**           | `AdaptiveRpsController.adjustRps()` (lines 65‑72) | Increases lock hold time; logging can be slow                |
| **Validation message bug**              | `AdaptiveRpsController.validate()`, line ~39      | Error message uses wrong property (`getMaxRps()` instead of `getTargetLatencyMs()`) |
| **Two‑pass stream iteration**           | `AdaptiveRpsController.adjustRps()`               | Computes `avgLatency` and `errorCount` in separate streams – minor inefficiency |
| **`increaseErrorThreshold` validation** | Requires `> 0`, forcing positive values           | Prevents zero‑error‑only increase policies                   |

#### 3.2 Testing & Observability

| Gap                          | Impact                                                       |
| ---------------------------- | ------------------------------------------------------------ |
| **No coverage metrics**      | Unknown test coverage; no badge for visibility               |
| **Weak assertions in tests** | `shouldIncreaseRpsWhenLatencyLowAndErrorRateNegligible` only checks `> 10.0` instead of verifying the cap (`== 100.0`) |
| **Missing edge‑case tests**  | No tests for `minRps` clamping, emergency brake, or no‑adjustment‑when‑`window.size() < minSamples` |
| **Limited observability**    | No metrics for per‑key CMS estimates, Watchdog drift count, or sequential blocked count |

#### 3.3 Performance & Scalability

| Gap                                  | Impact                                                       |
| ------------------------------------ | ------------------------------------------------------------ |
| **No published benchmarks**          | Design doc claims 10,000+ RPS but no performance test results to validate |
| **Redis CMS "production hardening"** | Design doc notes this is "future work" – critical for multi‑instance deployments |
| **No performance regression suite**  | Risk of degradation over time without automated benchmarks   |

#### 3.4 Developer Experience

| Gap                           | Impact                                                       |
| ----------------------------- | ------------------------------------------------------------ |
| **No `CONTRIBUTING.md`**      | Missing onboarding guide for new contributors                |
| **No issue/PR templates**     | Inconsistent issue reports and pull requests                 |
| **No code style enforcement** | Checkstyle present but not clearly documented                |
| **No `pom.xml` visibility**   | Dependency versions and vulnerability status not easily auditable |

#### 3.5 Community Building

| Gap                                | Impact                                                       |
| ---------------------------------- | ------------------------------------------------------------ |
| **No roadmap**                     | Community cannot see or influence the project's future direction |
| **No governance model**            | Unclear how decisions are made or how contributors can gain trust |
| **Limited external contributions** | No evidence of community engagement beyond the core team     |

------

### 4. Proposed Improvements

#### 4.1 Code Quality Improvements

**P1 – Move logging outside the write-lock**

java

```
// Current (inside lock):
currentRps.set(Math.max(minRps, currentRps.get() * emergencyFactor));
log.info("Emergency RPS brake: error_rate={} rps={}", errorRate, currentRps);

// Proposed (capture value, release lock, then log):
double newRps = Math.max(minRps, currentRps.get() * emergencyFactor);
currentRps.set(newRps);
// ... release lock ...
log.info("Emergency RPS brake: error_rate={} rps={}", errorRate, newRps);
```



**P1 – Fix validation error message**

java

```
// Current (incorrect):
throw new IllegalArgumentException("Target latency must be > 0: " + props.getMaxRps());

// Proposed:
throw new IllegalArgumentException("Target latency must be > 0: " + props.getTargetLatencyMs());
```



**P2 – Unify two‑pass stream iteration**

java

```
// Current: two separate streams
double avgLatency = window.stream().mapToLong(CompletionRecord::durationMs).average().orElse(0);
long errorCount = window.stream().filter(r -> !r.success()).count();

// Proposed: single pass
long sum = 0; long errors = 0;
for (CompletionRecord r : window) {
    sum += r.durationMs();
    if (!r.success()) errors++;
}
double avgLatency = (double) sum / window.size();
double errorRate = (double) errors / window.size();
```



**P3 – Relax `increaseErrorThreshold` validation to `>= 0`**

- Allows zero‑error‑only increase policies for strict environments.

------

#### 4.2 Testing Improvements

**P1 – Add coverage reporting**

- Integrate JaCoCo and publish coverage badge (e.g., via Codecov or Coveralls).
- Target: ≥ 80% line coverage.

**P1 – Strengthen existing tests**

java

```
// Current (weak):
assertThat(controller.getCurrentRps()).isGreaterThan(10.0);

// Proposed:
assertThat(controller.getCurrentRps()).isEqualTo(100.0); // verifies cap
```



**P2 – Add edge‑case test coverage**

- `minRps` clamping – verify RPS never drops below configured minimum.
- Emergency brake – error rate > threshold triggers `emergencyFactor`.
- No adjustment when `window.size() < minSamples`.
- `maxRps` cap – increase stops at configured maximum.

**P3 – Add performance benchmark suite**

- Use JMH to benchmark core operations (priority calculation, dispatch selection, CMS operations).
- Establish baseline metrics to track over time.

------

#### 4.3 Observability Improvements

**P2 – Add additional metrics**

| Metric                                   | Type    | Labels         | Purpose                            |
| ---------------------------------------- | ------- | -------------- | ---------------------------------- |
| `equalix.cms.estimate`                   | Gauge   | `fairness_key` | Per‑key in‑flight estimate         |
| `equalix.watchdog.drift_count`           | Counter | –              | Number of reconciled discrepancies |
| `equalix.sequential.blocked_count`       | Gauge   | –              | Number of blocked fairness keys    |
| `equalix.dispatcher.dispatched_total`    | Counter | –              | Total tasks dispatched             |
| `equalix.dispatcher.skipped_quota_total` | Counter | `fairness_key` | Tasks skipped due to quota         |

------

#### 4.4 Performance & Scalability Improvements

**P2 – Publish performance benchmarks**

- Document benchmark results in the design doc or a new `BENCHMARKS.md`.
- Include RPS, latency percentiles, and resource utilisation under load.

**P2 – Harden Redis CMS for production**

- Complete the "production hardening" noted in the design doc.
- Add health probes, connection pool tuning, and chaos‑testing.
- Document Redis failover behaviour and recovery procedures.

**P3 – Consider a `ScheduledExecutorService` for periodic adjustments**

- Currently adjusts on every completion. For extremely high throughput, sampling at fixed intervals (e.g., every 5 seconds) could reduce lock contention.

------

#### 4.5 Developer Experience Improvements

**P1 – Create `CONTRIBUTING.md`**
Include:

- How to set up the development environment.
- Code style and formatting guidelines.
- Testing requirements (unit + integration).
- PR submission process and expectations.

**P1 – Add issue and PR templates**

- **Issue template**: Bug report, feature request, enhancement proposal.
- **PR template**: Checklist for tests, documentation, and changelog.

**P2 – Document code style**

- Add a `style.md` or reference the Checkstyle configuration.
- Consider using `spotless` for automatic formatting.

**P2 – Publish `pom.xml` dependencies**

- Ensure `pom.xml` is visible and up‑to‑date.
- Use `mvn versions:display-dependency-updates` to track vulnerabilities.

------

#### 4.6 Community Building Improvements

**P2 – Publish a public roadmap**

- Use GitHub Projects or a `ROADMAP.md`.
- Include short‑term (next 3 months), medium‑term (6‑12 months), and long‑term (12+ months) goals.
- Accept community input via issues or discussions.

**P3 – Define a governance model**

- Document decision‑making processes (e.g., lazy consensus, maintainer approval).
- Define pathways for contributors to become maintainers.
- Adopt the CHAOSS project's practitioner guides for community health metrics.

**P3 – Create a `CODE_OF_CONDUCT.md`**

- Adopt the Contributor Covenant or similar.
- Essential for building a welcoming community.

------

### 5. Implementation Roadmap

| Phase                              | Timeline   | Items                                                        |
| ---------------------------------- | ---------- | ------------------------------------------------------------ |
| **Phase 1: Quick Wins**            | 1‑2 weeks  | Fix validation message; move logging outside lock; strengthen tests; add coverage reporting |
| **Phase 2: Developer Experience**  | 2‑4 weeks  | Create `CONTRIBUTING.md`; add issue/PR templates; document code style |
| **Phase 3: Observability**         | 4‑6 weeks  | Add additional metrics; publish benchmark results            |
| **Phase 4: Community Building**    | 6‑8 weeks  | Publish roadmap; define governance model; create `CODE_OF_CONDUCT.md` |
| **Phase 5: Performance Hardening** | 8‑12 weeks | Harden Redis CMS; implement performance regression suite     |

------

### 6. Success Criteria

| Metric                     | Target                                      |
| -------------------------- | ------------------------------------------- |
| Test coverage              | ≥ 80%                                       |
| Open issues/PRs            | < 10 open issues, < 5 open PRs              |
| Response time              | < 24 hours for first response on issues/PRs |
| External contributions     | ≥ 2 external contributors per quarter       |
| Documentation completeness | All major components documented             |

------

### 7. Conclusion

Equalix is already a **well‑engineered, production‑ready fairness scheduler** with exceptional documentation. The improvements outlined above focus on **polish, observability, community building, and performance validation** – elevating the project to an **outstanding open‑source project** that attracts contributors and inspires confidence in adopters.

**Priority summary:**

| Priority           | Items                                                        |
| ------------------ | ------------------------------------------------------------ |
| **P1 (Immediate)** | Fix validation bug; move logging outside lock; strengthen tests; add coverage reporting |
| **P2 (Near‑term)** | Create `CONTRIBUTING.md`; add issue/PR templates; publish benchmarks; add metrics |
| **P3 (Long‑term)** | Harden Redis CMS; define governance; publish roadmap         |

------

*Equalix – Equal chances. Fair shares.*