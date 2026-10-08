# Equalix — Mathematical Invariants

**Version:** 0.3
**Status:** Draft – incorporates review feedback  
**Purpose:** Formal foundation for the Equalix scheduler

---

## 1. System Model

Equalix is a capacity allocator for continuously backlogged multi-tenant workloads.

Let:

- $K$ — set of fairness keys / tenants.
- $k \in K$ — one fairness key.
- $w_k > 0$ — configured weight of key $k$.
- $Q_k \in \mathbb{N} \cup \{\infty\}$ — concurrency quota (infinite allowed).
- $F_k(t)$ — authoritative number of in-flight tasks at time $t$.
- $\hat{F}_k(t)$ — approximate in-flight estimate used by the scheduler.
- $q_k(t)$ — number of queued/eligible tasks.
- $W_x(t)$ — waiting time of task $x$.
- $R(t)$ — global dispatch-rate budget.
- $C_{\max}$ — global in-flight capacity.
- $F_{\text{global}}(t)$ — total authoritative in-flight work.
- $T_k(t)$ — accumulated virtual scheduling time (persistent fairness state).
- $p(t)$ — in-flight pressure coefficient.
- $\lambda$ — aging coefficient.
- $P_x(t)$ — effective priority of task $x$.

The fundamental distinction is:

> **Approximate state may influence optimization, but authoritative state defines safety.**

---

## 2. Invariant Hierarchy

Equalix has four conceptual layers:

1. **Safety** — constraints that must never be intentionally violated.
2. **Liveness** — eligible work must eventually receive service.
3. **Fairness** — available capacity should converge toward configured weighted shares.
4. **Optimization** — reduce scheduling latency, contention, executor overload, and fairness error.

The hierarchy is:

$$
\text{Safety} > \text{Liveness} > \text{Fairness} > \text{Optimization}
$$

A lower-level objective must never override a higher-level invariant.

---

## 3. Hard Concurrency Quota

For every fairness key $k$:

$$
F_k(t) \le Q_k
$$

for all valid times $t$.

If $Q_k = \infty$, the inequality is trivially satisfied and eligibility is always true.

A task belonging to $k$ is normally eligible only when:

$$
F_k(t) < Q_k
$$

Define quota eligibility:

$$
E_k(t) =
\begin{cases}
1 & F_k(t) < Q_k \\
0 & F_k(t) \ge Q_k
\end{cases}
$$

with the convention that if $Q_k = \infty$, then $E_k(t) = 1$ for all $t$.

### Interpretation

Priority answers:

> Which eligible task should run first?

Eligibility answers:

> Is the task allowed to run at all?

Quota is therefore a **hard safety constraint**, not a scheduling preference.

---

## 4. In-Flight Conservation

For each fairness key $k$:

$$
F_k(t_2) = F_k(t_1) + S_k(t_1,t_2) - L_k(t_1,t_2)
$$

where:

- $S_k$ — tasks entering the in-flight state.
- $L_k$ — tasks leaving the in-flight state (replaces earlier $T_k$ to avoid ambiguity with virtual time).

Equivalently:

$$
\Delta F_k = \Delta S_k - \Delta L_k
$$

A task should enter the in-flight population once and leave it once, subject to explicitly defined retry/reconciliation semantics.

This invariant is the foundation for quota correctness.

---

## 5. Weighted Long-Term Fairness

Let:

$$
D_k(W)
$$

be the number of dispatches for key $k$ during scheduling window $W$.

For continuously backlogged keys under unconstrained conditions:

$$
B_k(t) = 1
$$

and where quotas, global capacity, executor health, and explicit suspension do not constrain the key, Equalix seeks:

$$
\lim_{|W| \to \infty} \frac{D_k(W)}{\sum_j D_j(W)} = \frac{w_k}{\sum_j w_j}
$$

The expected weighted share is:

$$
E_k = \frac{w_k}{\sum_j w_j}
$$

### Example

For:

$$
w_A = 1,\quad w_B = 2,\quad w_C = 7
$$

the expected shares are:

$$
E_A = 10\%,\quad E_B = 20\%,\quad E_C = 70\%
$$

The guarantee applies to **available capacity under comparable demand**, not to absolute task counts.

---

## 6. Fairness Error

Define the observed share:

$$
S_k(W) = \frac{D_k(W)}{\sum_j D_j(W)}
$$

Then define per-key fairness error:

$$
\epsilon_k(W) = |S_k(W) - E_k|
$$

System-wide maximum fairness error:

$$
\epsilon_{\max}(W) = \max_k \epsilon_k(W)
$$

For an implementation with bounded approximation and discrete scheduling effects, a practical invariant is:

$$
\limsup_{|W| \to \infty} \epsilon_{\max}(W) \le \epsilon
$$

where $\epsilon$ is an experimentally established error bound.  
The value of $\epsilon$ should be measured rather than assumed.

### Measured bound (EQX-1)

`ProportionalFairnessIntegrationTest` runs the real priority calculator and dispatcher against PostgreSQL with three continuously backlogged tenants, $w_A : w_B : w_C = 1 : 2 : 7$. The conditions follow §5: no quotas, adaptive RPS off, and no anti-starvation promotion. Each tick dispatches up to 20 tasks, and every dispatched task is completed and replaced. Two scenarios run for 10,000 dispatches each:

- **No pressure:** dispatched tasks complete within the tick, so only virtual time $T_k$ orders the queue.
- **With pressure:** dispatched tasks are still in flight at the next priority calculation, so the CMS pressure term $p \cdot \hat{F}_k / w_k$ is active.

Prefix $\epsilon_{\max}$ is measured over the first $W$ dispatches. Sliding $\epsilon_{\max}$ is the worst over every contiguous window of $W$ dispatches.

| W | No pressure: prefix | No pressure: sliding | With pressure: prefix | With pressure: sliding |
|---|---|---|---|---|
| 10 | 0 | 0 | 0 | 0.10 |
| 25 | 0.02 | 0.02 | 0.02 | 0.06 |
| 100 | 0 | 0 | 0 | 0.01 |
| 1,000 | 0 | 0 | 0 | 0.001 |
| 10,000 | 0 | 0 | 0 | 0 |

At $W = 10{,}000$ the observed shares are exactly $S_A = 10\%$, $S_B = 20\%$, $S_C = 70\%$. The results are deterministic across runs.

In task counts, the error is at most 0.5 task without pressure. That is the rounding floor, because $W \cdot E_k$ is not always a whole number. With pressure it is at most 1.5 tasks. The asserted empirical bound is therefore:

$$
\epsilon_{\max}(W) \le \frac{2}{|W|}
$$

This means no tenant is ever more than two tasks ahead of or behind its weighted share, in any window. For $W = 10{,}000$ this gives $\epsilon \le 0.0002$.

As a control, the same experiment with the virtual-time term disabled (`quantum = 0`) gives shares of 33/33/33 without pressure and 0.4/0.4/99.2 with pressure only. So the persistent $T_k$ (§7) is what makes the weighted shares hold.

Scope: the bound covers equal-cost tasks under the conditions of §5. Quota-constrained keys (§25.6), aging (EQX-4) and hierarchical keys (EQX-7) need to be measured again with the same harness.

---

## 7. Virtual Time (Persistent Fairness State)

The scheduler distinguishes **historical allocation state** from **current executor pressure**.

Let:

$$
T_k(t)
$$

be the accumulated virtual scheduling position for fairness key $k$.  
This is a persistent value that survives across scheduling cycles.

For equal-cost tasks, a classical weighted virtual-time update is:

$$
T_k \leftarrow T_k + \frac{1}{w_k}
$$

after dispatching one task for $k$.

For a task with scheduling cost $s_x$:

$$
T_k \leftarrow T_k + \frac{s_x}{w_k}
$$

Higher weights therefore advance virtual time more slowly.

### Interpretation

Virtual time represents:

> How far this key has progressed through its proportional share of scheduling service.

This gives weighted fairness a persistent state rather than deriving fairness only from instantaneous load.

> **Note on implementation (EQX-3):** Equalix persists $T_k$ in `client_virtual_time` and uses self-clocked fair queueing. When task $x$ of key $k$ is queued it receives a finish tag $F_x = \max(F_{k,\text{last}}, V) + s_x/w_k$, where $F_{k,\text{last}}$ is the key's previous tag and $V$ is the system virtual time (the highest dispatched tag, stored in `scheduler_virtual_clock`). On dispatch, $T_k \leftarrow \max(T_k, F_x)$ and $V \leftarrow \max(V, F_x)$. For a continuously backlogged key this is exactly $T_k \leftarrow T_k + s_x/w_k$. The $V$ floor stops an idle key from accumulating credit it could spend in a burst. Tags are scaled by `app.queue.virtual-time.quantum`. All tasks currently have $s_x = 1$.

---

## 8. Weighted Priority

Equalix combines accumulated virtual time with current in-flight pressure and task aging.

The base priority is:

$$
P_k^{\text{base}}(t) = T_k(t) + I_k(t)
$$

where $I_k(t)$ is the in-flight pressure defined below.

Lower priority values are selected first.

---

## 9. In-Flight Pressure

Let:

$$
\hat{F}_k(t)
$$

be the scheduler's approximate in-flight count.

The initial Equalix pressure model is:

$$
I_k(t) = p(t) \frac{\hat{F}_k(t)}{w_k}
$$

where:

- $p(t)$ — configurable pressure coefficient.
- $\hat{F}_k$ — approximate in-flight count.
- $w_k$ — tenant weight.

Thus:

$$
P_k^{\text{base}}(t) = T_k(t) + p(t) \frac{\hat{F}_k(t)}{w_k}
$$

### Generalized model

A future implementation may use:

$$
I_k(t) = p(t) \frac{\hat{F}_k(t)^\alpha}{w_k^\beta}
$$

with:

$$
\alpha, \beta > 0
$$

The initial model corresponds to:

$$
\alpha = 1,\quad \beta = 1
$$

The generalized form should remain a design parameter rather than an implementation requirement.

---

## 10. Aging / Anti-Starvation

For task $x$:

$$
W_x(t) = t - \text{arrival}_x
$$

where $W_x$ is the time the task has been waiting.

A linear aging function is:

$$
A_x(t) = \lambda W_x(t)
$$

where:

$$
\lambda > 0
$$

Since lower priority is better, aging reduces effective priority:

$$
P_x(t) = P_k^{\text{base}}(t) - \lambda W_x(t)
$$

Therefore:

$$
P_x(t) = T_k(t) + p(t) \frac{\hat{F}_k(t)}{w_k} - \lambda W_x(t)
$$

As waiting time increases, the task becomes progressively more likely to be selected.

### Implementation and simulation (EQX-4)

`app.queue.aging.policy` selects $A(W)$: `none` (default), `linear` $\lambda W$, `log` $\lambda \ln(1+W)$, or `power` $\lambda W^\gamma$. $W$ is in seconds and $A$ is in priority units, where one weight-1 task costs `quantum` virtual-time units. Non-linear aging changes the relative order of queued tasks over time, so the dispatcher evaluates $P_x(t) = P^{\text{base}} - A(W_x(t))$ at selection time. It does this over a candidate pool: the best tasks by stored $P^{\text{base}}$ plus the oldest tasks. The `max-queued-time-ms` promotion remains a hard backstop.

**Virtual time under aging.** A task promoted by aging is served ahead of its tag $F_x$. The key is still charged in full ($T_k \leftarrow \max(T_k, F_x)$), but the system virtual time only advances to the aged position: $V \leftarrow \max(V, F_x - A(W_x))$. Without this, one promoted task drags $V$ forward, and every key's new work restarts from that inflated $V$. In the burst simulation below, advancing $V$ to the full tag lowers the heavy tenant's minimum share over any 100 dispatches to 37% instead of 65% with `power`, and to 57% instead of 86% with `linear`.

`AgingSimulationTest` runs the production VirtualTimeService and AgingService with tenants at weights 1 and 9 and a capacity of 10 tasks/s. Every policy is calibrated so that $A(30\,\text{s})$ equals 10 weight-1 tasks:

- linear: $\lambda = 333$
- log: $\lambda = 2912$
- power: $\lambda = 11.1,\ \gamma = 2$

**Steady backlog.** Both tenants keep 50 tasks queued. The weight-1 share stays at 10% under every policy (sliding $\epsilon_{\max}(W=100) \le 0.01$). With constant queue depth, each tenant's waiting time is constant, so aging adds only a constant offset per tenant, and virtual time keeps service rates proportional to weight. Aging cannot fracture long-term weighted shares.

**Structural backlog.** The weight-9 tenant is backlogged, and the weight-1 tenant submits a burst of 300 tasks:

| Policy | Burst wait p50 | Burst wait max | Weight-9 share while burst drains | Lowest weight-9 share over any 100 dispatches |
|---|---|---|---|---|
| none | 149 s | 299 s | 90% | 89% |
| linear | 115 s | 230 s | 87% | 86% |
| log | 137 s | 285 s | 89% | 83% |
| power ($\gamma=2$) | 82 s | 131 s | 77% | 65% |

Findings:

- `power` promotes long waits most aggressively: the maximum wait drops by 56% at equal 30 s credit.
- `log` gives a front-loaded boost that flattens, so it barely helps long waits.
- Under every policy, the heavy tenant keeps the majority of capacity in every 100-dispatch window. Short-term weighted quotas are bent but not broken.

The simulation asserts these properties.

---

## 11. Deriving a Starvation Bound

Suppose task $x$ has initial priority $P_0$, and a competing task has priority $P_c$.

Task $x$ becomes preferable when:

$$
P_0 - \lambda t \le P_c
$$

Therefore:

$$
\lambda t \ge P_0 - P_c
$$

and:

$$
t \ge \frac{P_0 - P_c}{\lambda}
$$

This provides a direct relationship between:

- initial scheduling disadvantage,
- aging rate,
- maximum waiting time.

The actual starvation bound additionally depends on continuous availability of dispatch capacity and the behavior of other tasks.

---

## 12. Quota Eligibility and Priority Are Separate

The scheduler should conceptually perform:

### Step 1 — eligibility

$$
E_k(t) = [F_k(t) < Q_k]
$$

(if $Q_k = \infty$, always true)

### Step 2 — priority

$$
P_x(t) = T_k(t) + p(t) \frac{\hat{F}_k(t)}{w_k} - \lambda W_x(t)
$$

### Step 3 — selection

Choose the minimum-priority task among eligible tasks.

This separation prevents fairness logic from accidentally overriding hard safety constraints.

---

## 13. Global RPS Capacity

Let:

$$
R(t)
$$

be the current global dispatch rate in tasks/second.

For scheduling interval $\Delta t$, the rate-derived budget is:

$$
B_R(t,\Delta t) = \lceil R(t)\Delta t \rceil
$$

If Equalix also has a global concurrency limit $C_{\max}$:

$$
F_{\text{global}}(t) = \sum_k F_k(t)
$$

and free concurrency is:

$$
B_C(t) = C_{\max} - F_{\text{global}}(t)
$$

The dispatch budget becomes:

$$
B(t) = \max(0, \min(B_R, B_C))
$$

Thus:

$$
D(t,t+\Delta t) \le B(t)
$$

---

## 14. Capacity Control vs. Fairness Control

Equalix contains two distinct control planes.

### Capacity control

$R(t)$ determines:

> How much work can be admitted to the executor.

### Fairness control

$P_x(t)$ determines:

> Which eligible task receives the next unit of capacity.

Therefore:

$$
\text{Adaptive RPS allocates capacity}
$$

while:

$$
\text{Virtual scheduling allocates available capacity among tenants}
$$

This distinction should remain explicit in the architecture.

---

## 15. Tie-Breaking

If two tasks have equal effective priority:

$$
P_x = P_y
$$

Equalix should use deterministic secondary ordering.

Define:

$$
x \prec y
$$

iff, lexicographically:

$$
(P_x, \text{arrival}_x, \text{id}_x) < (P_y, \text{arrival}_y, \text{id}_y)
$$

Therefore the ordering is:

1. lowest effective priority,
2. oldest arrival,
3. deterministic task ID.

Formally:

$$
x^* = \arg\min_x (P_x, \text{arrival}_x, \text{id}_x)
$$

This avoids relying on unspecified database ordering.

---

## 16. Approximate In-Flight State

Let:

$$
F_k(t)
$$

be the authoritative count and:

$$
\hat{F}_k(t)
$$

the approximate scheduler estimate.

Define CMS error:

$$
e_k(t) = \hat{F}_k(t) - F_k(t)
$$

Therefore:

$$
\hat{F}_k(t) = F_k(t) + e_k(t)
$$

The scheduler does not assume $e_k = 0$.

---

## 17. Propagation of CMS Error

The pressure term is:

$$
I_k = p \frac{\hat{F}_k}{w_k}
$$

Substituting:

$$
\hat{F}_k = F_k + e_k
$$

gives:

$$
\hat{I}_k = p \frac{F_k + e_k}{w_k}
$$

Therefore:

$$
\hat{I}_k - I_k = p \frac{e_k}{w_k}
$$

The resulting priority error is:

$$
\hat{P}_k - P_k = p \frac{e_k}{w_k}
$$

assuming virtual time and aging are unchanged.

If:

$$
|e_k| \le E
$$

then:

$$
|\hat{P}_k - P_k| \le p \frac{E}{w_k}
$$

This gives a direct way to measure how approximate accounting affects scheduling.

**Validated (EQX-2):** `CmsErrorPropagationTest` prioritises the same 2,000 tasks twice with the production `PriorityCalculatorService`: once with a heavily loaded $1024 \times 3$ sketch ($E = 14$, 75% of keys overestimated) and once with exact counts. For $p \in \{10, 100, 1000\}$ and weights $\{0.5, 1, 2, 7\}$, every task satisfies $|\hat{P}_k - P_k| \le p \cdot |e_k| / w_k + 1$, and therefore also $\le p E / w_k + 1$. The $+1$ comes from the integer truncation of the pressure term.

---

## 18. Overestimation vs. Underestimation

### Overestimation

If:

$$
e_k > 0
$$

then:

$$
\hat{F}_k > F_k
$$

and:

$$
\hat{P}_k > P_k
$$

The key receives excessive scheduling pressure.

Effect:

> Temporary under-allocation of capacity.

This is generally a fairness/performance error rather than a safety violation.

### Underestimation

If:

$$
e_k < 0
$$

then:

$$
\hat{F}_k < F_k
$$

and:

$$
\hat{P}_k < P_k
$$

The key may receive more scheduling opportunities than its actual load would suggest.

Effect:

> Temporary over-allocation of capacity.

This is why the approximate count must not be the sole source of hard quota enforcement.

---

## 19. Critical CMS Safety Boundary

The fundamental rule is:

$$
\text{CMS} \rightarrow \text{optimization}
$$

and:

$$
\text{authoritative state} \rightarrow \text{safety}
$$

In particular:

$$
\hat{F}_k < Q_k
$$

must **not** be interpreted as proof that:

$$
F_k < Q_k
$$

Instead, quota enforcement must ultimately rely on authoritative state.

CMS error may therefore affect:

- scheduling order,
- fairness precision,
- temporary load distribution,

but must not intentionally invalidate:

- hard concurrency limits,
- durable state transitions,
- accounting invariants.

---

## 20. CMS Mathematical Caveat and Practical Approach

Classical Count-Min Sketch guarantees are normally stated for non-negative frequency updates.

If Equalix performs both:

$$
+1
$$

and:

$$
-1
$$

updates in the same sketch, classical Count-Min Sketch guarantees should **not automatically be claimed**.

Therefore Equalix should currently define:

$$
e_k(t) = \hat{F}_k(t) - F_k(t)
$$

and measure its empirical distribution:

- mean error,
- maximum error,
- p95 error,
- p99 error,
- underestimation frequency,
- overestimation frequency.

A formal signed-update error bound should only be introduced after selecting and proving the properties of an appropriate data structure.

In the interim, the system employs a **Watchdog** that periodically reconstructs the approximate counts from the authoritative task table, bounding drift. This pragmatic approach maintains safety while empirical data on error distributions is collected.

### Measured distribution (EQX-2)

**Why signed updates are safe here.** Equalix updates the sketch in the *strict turnstile* model. Every $-1$ (completion) matches an earlier $+1$ (dispatch) of the same key, so every true count stays $F_k \ge 0$. Under that condition, the classical Count-Min guarantees still hold:

- **No underestimation:** every cell is at least the key's own count, so $e_k \ge 0$.
- **Bounded overestimation:** $e_k \le 2N/w$ with probability $\ge 1 - 2^{-d}$.

Here $N$ is the **current** total in flight, not the number of updates made since the last rebuild. Error therefore does not accumulate with traffic. Underestimation can only come from accounting faults, where an update is applied without its matching DB change.

**Experiment.** `CmsSignedUpdateErrorExperimentTest` sends a seeded stream of $+1$/$-1$ updates to `CountMinSketchAdapter`:

- about 5,000 tasks in flight, over Zipf-distributed keys (exponent 1.1);
- one watchdog window: 300,000 updates, i.e. 5 minutes at 1,000 updates/s;
- 60 samples per window, of every key that was ever dispatched.

| Sketch | Keys | Samples | Mean $e$ | p95 $\lvert e\rvert$ | p99 $\lvert e\rvert$ | Max | Over | Under | $2N/w$ | Samples > $2N/w$ |
|---|---|---|---|---|---|---|---|---|---|---|
| $1024 \times 3$ (test) | 1,000 | 58,651 | 0.17 | 1 | 3 | 13 | 10.5% | 0 | 9.6 | 0.055% |
| $1024 \times 3$ (test) | 10,000 | 373,857 | 0.55 | 2 | 4 | 25 | 38.6% | 0 | 9.7 | 0.042% |
| $1024 \times 3$ (test) | 50,000 | 699,283 | 0.76 | 2 | 4 | 23 | 51.6% | 0 | 9.7 | 0.031% |
| $65536 \times 5$ (production) | 1k / 10k / 50k | up to 699,283 | 0 | 0 | 0 | 0 | 0 | 0 | 0.15 | 0 |

With exact accounting:

- **No underestimation** in about 2.4 million samples.
- The $2N/w$ bound is exceeded far less often than the allowed $2^{-d}$.
- The **production-size sketch was exact in every sample, even with 50,000 keys.** Error depends on the number of keys *currently in flight* (at most $N$), not on how many keys exist.

Figures are for the 64-bit key hashing introduced after EQX-2 (see below). With the earlier 32-bit hashing, the sequential test ids `tenant-0…tenant-N` were spread more evenly than a random hash would spread them, so the test-size tail looked lighter (max 13–14, about 0.01% of samples above $2N/w$). The figures above are the honest random-hash behaviour. The production-size results are unchanged.

**Accounting faults.** In EQX-2, `cms.add` ran inside the dispatch and completion transactions. A rollback therefore left a phantom $+1$ (a dispatch that never happened) or an extra $-1$ (a completion rolled back and then retried). Injecting each fault at 0.1% gives:

| Sketch | Mean $e$ | Min | Max | p99 $\lvert e\rvert$ | Over | Under |
|---|---|---|---|---|---|---|
| $65536 \times 5$ | 0.001 | $-4$ | $+5$ | 1 | 0.67% | 0.52% |
| $1024 \times 3$ | 0.55 | $-4$ | $+27$ | 4 | 38.8% | 0.48% |

Faults are never reversed within a window, so drift grows in both directions until the watchdog rebuild resets it. After the rebuild, the error is back to exact-accounting behaviour.

**Fixed:** CMS updates are now buffered per transaction and applied only after commit (`TransactionAwareCmsProvider`), so rollbacks leave the sketch untouched. The fault scenario stays in the experiment to show what drift looks like if updates are ever lost, for example on a crash between the commit and the sketch update.

![CMS error distribution](images/eqx-2/error-histogram.png)

![CMS error between rebuilds](images/eqx-2/error-timeline.png)

The charts are generated by `docs/samples/plot_cms_error.py` from the CSV output in `target/eqx-2/`.

**Hash-code collisions (fixed).** In EQX-2, every sketch row was derived from the 32-bit `String.hashCode()`. Keys with equal hash codes shared all $d$ cells, and no sketch size could separate them. For example, with 40 tasks in flight for `tenant-BB`, an idle `tenant-Aa` was estimated at 40. Rows are now derived from a 64-bit hash of the key's UTF-8 bytes (`CmsKeyHasher`), so two keys share all rows only if their 64-bit hashes collide, with probability of about $K^2 / 2^{65}$. The cell layout changed, so the Redis sketch moved to a versioned key (`…:v2`).

**Restarts (fixed).** A restarted instance used to start with an empty local sketch and underestimate every key until the first watchdog run. The sketch is now rebuilt from in-flight tasks at startup.

**Consequences:**

- **Drift alerts (EQX-5, implemented).** The watchdog publishes $e_k$ per key (`equalix.cms.estimation.drift`) and in aggregate just before each rebuild; see Operations. With a production-size sketch and exact accounting, $e_k = 0$ is the expected value. Any sustained $\lvert e_k\rvert \ge 1$ points to lost updates, not sketch noise. For small sketches, use the measured p99 (4 for $1024 \times 3$ at 5,000 in flight) as the noise floor.
- **Priority error.** Through §17, a p99 error of 4 bounds the priority error at $4p/w_k$.
- **Follow-ups (done):** CMS updates apply after commit, row hashes come from a 64-bit hash of the key bytes, and the sketch is rebuilt at startup.

---

## 21. Complete Equalix Priority Function

Combining the mechanisms:

$$
P_x(t) = T_k(t) + p(t) \frac{\hat{F}_k(t)}{w_k} - \lambda W_x(t)
$$

where task $x$ belongs to key $k$.

The components have distinct responsibilities:

$$
\underbrace{T_k}_{\text{weighted fairness}}
+ \underbrace{p \frac{\hat{F}_k}{w_k}}_{\text{in-flight pressure}}
- \underbrace{\lambda W_x}_{\text{anti-starvation}}
  $$

This separation is central to the Equalix model.

---

## 22. Complete Scheduling Algorithm

For each scheduling cycle:

### 1. Determine available capacity

$$
B(t) = \max\left(0, \min\left[\lceil R(t)\Delta t \rceil, C_{\max} - F_{\text{global}}(t)\right]\right)
$$

### 2. Determine eligible tasks

$$
E_k(t) = [F_k(t) < Q_k]
$$

(with $Q_k = \infty$ always eligible)

### 3. Calculate effective priority

$$
P_x(t) = T_k(t) + p(t) \frac{\hat{F}_k(t)}{w_k} - \lambda W_x(t)
$$

### 4. Select

$$
x^* = \arg\min_x (P_x, \text{arrival}_x, \text{id}_x)
$$

among eligible tasks.

### 5. Dispatch

Dispatch up to $B(t)$ tasks.

### 6. Update virtual time

For equal-cost tasks:

$$
T_k \leftarrow T_k + \frac{1}{w_k}
$$

### 7. Update authoritative accounting

$$
F_k \leftarrow F_k + 1
$$

when a task becomes genuinely in-flight.

Completion decrements the authoritative count according to the task lifecycle semantics.

---

## 23. Core Equalix Invariants

The mathematical model can therefore be summarized by the following invariants.

## Safety

$$
F_k(t) \le Q_k
$$

for every key $k$.

## Global capacity

$$
F_{\text{global}}(t) \le C_{\max}
$$

and:

$$
D(t,t+\Delta t) \le \lceil R(t)\Delta t \rceil
$$

## Liveness

For an eligible task with continuously available dispatch capacity:

$$
W_x(t) \le W_{\max}
$$

subject to the configured aging policy and system assumptions.

## Weighted fairness

For continuously backlogged and unconstrained keys:

$$
\limsup_{|W| \to \infty} |S_k(W) - E_k| \le \epsilon
$$

where:

$$
E_k = \frac{w_k}{\sum_j w_j}
$$

## Approximation

$$
\hat{F}_k = F_k + e_k
$$

with CMS error affecting optimization but not authoritative safety.

---

## 24. Design Principle

The Equalix scheduler can be summarized as:

$$
\text{Priority} = \text{Fairness} + \text{Pressure} - \text{Aging}
$$

more explicitly:

$$
P_x = \underbrace{T_k}_{\text{historical weighted allocation}}
+ \underbrace{p \frac{\hat{F}_k}{w_k}}_{\text{current load}}
- \underbrace{\lambda W_x}_{\text{waiting-time compensation}}
  $$

while:

$$
\text{Eligibility} = \text{authoritative quota state}
$$

and:

$$
\text{Capacity} = \text{adaptive global RPS/concurrency budget}
$$

This separation gives each mechanism one clear responsibility.

---

## 25. Open Design Questions

The following remain explicitly unresolved in v0.2. Some are addressed with interim strategies.

### 25.1 Virtual time model

Should Equalix use:

$$
T_k
$$

as persistent accumulated virtual time, or adopt the simpler current-time formulation?

**Resolved (EQX-3):** Equalix uses persistent accumulated virtual time $T_k$ with a system virtual-time floor $V$ (see §7). Long-term convergence to the weighted shares is validated in EQX-1.

### 25.2 Pressure coefficient

How should:

$$
p(t)
$$

relate to executor latency, error rate, and global RPS?

**Interim:** $p(t) = 1000 / R(t)$ is used; stability analysis and adaptive tuning are ongoing.

### 25.3 Aging function

Should aging be:

$$
A(W) = \lambda W
$$

or bounded/non-linear?

Potential alternatives:

$$
A(W) = \lambda \log(1+W)
$$

or:

$$
A(W) = \lambda W^\gamma
$$

**Implemented (EQX-4):** `none`, `linear`, `log` and `power` are configurable; the default is `none`. The trade-offs measured by simulation are in §10. Use `power` with $\gamma > 1$ when long waits must be bounded, and `log` when disruption must stay minimal.

### 25.4 CMS semantics

What data structure provides useful and defensible error bounds when counters can both increment and decrement?

**Answered for Equalix's usage (EQX-2):** Updates follow the strict turnstile model, so the standard Count-Min sketch keeps its guarantees: $e_k \ge 0$, and $e_k \le 2N/w$ with probability $\ge 1 - 2^{-d}$, where $N$ is the current in-flight total. The measurements in §20 confirm this. Drift in both directions could only come from accounting faults and hash-code collisions. Both are fixed: updates apply after commit, and keys use 64-bit hashing. The Watchdog interval bounds any remaining lost update. A general-turnstile structure is not needed unless updates stop being paired.

### 25.5 Fairness window

What constitutes a "sufficiently large" window $W$?

**Measured (EQX-1):** Under continuous backlog, weighted shares hold to within 2 tasks over any window ($\epsilon_{\max}(W) \le 2/|W|$, see §6). Windows of a few hundred dispatches are therefore already within 1%. Production benchmarks with irregular arrivals are still pending.

### 25.6 Fairness under quota constraints

How should expected weighted shares be calculated when some tenants are continuously quota-constrained?

**Note:** The guarantee only applies when keys are not quota-limited. Future work may extend the model to incorporate quota pressure.

### 25.7 Adaptive controller stability

What conditions prevent oscillation in:

$$
R(t)
$$

when executor latency and error rates fluctuate?

**Evaluated (EQX-6):** Stability is achieved by rate-limiting the controller, dampening direction reversals, and smoothing the latency signal. A formal stability proof is still open.

**Why the original controller over-throttled.** It re-evaluated its 100-completion window after *every* completion and applied a multiplicative step each time. One latency spike stays in the window for 100 completions, so it was applied up to 100 times ($\times 0.9$ each, or $\times 0.5$ for the error brake) until $R(t)$ hit `min-rps`. At that rate, the count-based window then took minutes to refresh. Smoothing or a dampener alone cannot fix this: both still act on every completion.

**Controls.**

- **Adjustment interval $\Delta$:** $R(t)$ changes at most once per $\Delta$. Each evaluation uses the mean latency of the completions since the previous one.
- **Latency EMA:** $\tilde{L} \leftarrow \alpha L + (1-\alpha)\tilde{L}$. The time constant $\approx \Delta/\alpha$, independent of the completion rate.
- **Dead-band dampener:** a reversal needs $c$ consecutive agreeing evaluations, and the dead band resets the count. The emergency brake bypasses it but is still limited to one step per $\Delta$.

**Simulation.** `AdaptiveRpsStabilitySimulationTest` runs the real controller in closed loop against an executor model:

- latency $\text{base}/(1 - \rho)$ with base 100 ms, target 200 ms and $\pm 20\%$ dead band, so the ideal operating point is $\rho = 0.5$;
- log-normal noise on every sample;
- errors when overloaded;
- a 25-minute measurement.

Four workloads:

- *transient*: $\times 4$ latency for 2 s every 20 s;
- *long spikes*: $\times 4$ for 10 s every 120 s;
- *capacity loss*: capacity halves for 5 minutes;
- *low-rate noisy*: capacity 6/s, $\sigma = 0.8$.

Over-throttled means below half the ideal rate; overloaded means load-induced latency above twice the target.

| Configuration $(\alpha, \Delta, c)$ | Transient: over-throttled / reversals per h | Long spikes: over-throttled | Capacity loss: over-throttled / overloaded | Low-rate: over-throttled / reversals per h |
|---|---|---|---|---|
| stock (1, 0, 1) | 97.1% / 55 | 98.1% | 58.5% / 0.6% | 95.7% / 17 |
| stock + EMA (0.7, 0, 1) | 96.9% / 55 | 97.9% | 58.3% / 0.6% | 95.7% / 17 |
| stock + dampener (1, 0, 3) | 98.0% / 55 | 98.3% | 58.7% / 0.5% | 95.7% / 17 |
| interval (1, 2 s, 1) | 0% / 353 | 3.9% | 0.1% / 0.3% | 0.3% / 377 |
| interval + EMA (0.7, 2 s, 1) | 0% / 353 | 7.0% | 0.1% / 0.3% | 0% / 247 |
| interval + dampener (1, 2 s, 3) | 0% / 0 | 0% | 0.3% / 0.3% | 1.1% / 72 |
| **recommended (0.7, 2 s, 3)** | **0% / 60** | **0%** | **0.4% / 0.3%** | **0% / 62** |

The stock controller runs at 2.2 rps on average against an ideal of 25, and at 0.5 rps against 3 in the low-rate case. The adjustment interval removes the collapse. The dampener cuts reversals under transient spikes and noise by 80–100%. The EMA mainly helps slow, noisy executors, where each interval holds only a few samples. On the high-rate spike workloads, $\alpha = 1$ is marginally better, so $\alpha$ is a tuning choice. A $3 \times 3 \times 4$ grid over $\Delta \in \{0.5, 1, 2\}\,\text{s}$, $\alpha \in \{1, 0.7, 0.5\}$ and $c \in \{1,\ldots,4\}$ favoured $\Delta = 2\,\text{s}$: a longer interval gives one spike fewer steps. The recommended settings held on four further seeds, with over-throttling $\le 1.4\%$ and overload $\le 0.3\%$ in every workload. The simulation asserts these properties.

**Coupling.** $p(t) = 1000/R(t)$ feeds the priority pressure term (§9), so a steadier $R(t)$ also steadies priorities. EQX-7's backpressure cascading should reuse these controls rather than add a second controller.

---

## 26. Intended Evolution

This document should be treated as a mathematical contract under development.

The intended progression is:

$$
\text{Model} \rightarrow \text{Simulation} \rightarrow \text{Implementation} \rightarrow \text{Benchmark} \rightarrow \text{Refinement}
$$

Before claiming a formal guarantee, Equalix should validate the corresponding invariant through simulation and load testing.

The most important next experiment is to demonstrate weighted fairness (done in EQX-1, see §6 for the measured bound):

$$
w_A : w_B : w_C = 1 : 2 : 7
$$

and measure:

$$
S_A, S_B, S_C
$$

over increasing scheduling windows.

The second experiment should measure how:

$$
e_k = \hat{F}_k - F_k
$$

propagates into fairness error.

A third experiment should evaluate the stability of the adaptive RPS controller under varying load.

---

## 27. Hierarchical Virtual Time (EQX-7)

With `app.queue.fairness-mode: hierarchical`, a fairness key is a path through a tenant tree, and every layer is scheduled fairly among its siblings. With layers organization → department:

- `acme/sales` is organization `acme/` → department `acme/sales`;
- extra segments fold into the last layer;
- a one-segment key such as `smallclub` is a leaf directly under the root, competing with `acme/`.

This solves the two flat-mode failures:

- With one key per organization, a hot department takes the organization's whole share: it is FIFO inside the key.
- With one key per department, an organization with $n$ departments claims $n$ times the share of a single-key tenant.

### Model

Every node $v$ has a weight $w_v$ and a virtual runtime $\tau_v$, measured in its parent's virtual time; this is CFS group scheduling. For each dispatch slot, selection descends from the root. At each node it picks the backlogged child $c$ minimising

$$
\tau_c + \frac{q}{w_c} + p(t) \frac{\hat{F}_c}{w_c}
$$

(finish time after one more task, plus that node's in-flight pressure), with ties broken by key. Every node on the chosen leaf's path is then charged $\tau_v \leftarrow \tau_v + q/w_v$.

**Share.** Among the backlogged children of a parent $P$, child $c$ receives the fraction $w_c / \sum w_j$ of $P$'s service. A leaf's share of the whole system is the product of these fractions along its path.

**Idle children.** Each parent keeps a floor $m_P = \max(m_P, \min_{\text{backlogged child } c} \tau_c)$, taken after each tick's charges. A child that returns from idle starts at $\max(\tau_c, m_P)$, so idleness does not bank credit. This is the hierarchical counterpart of the system virtual time $V$ in §7. Children that stay backlogged are never below $m_P$, so the floor never takes anything from them.

**Pressure per layer.** The sketch also counts every internal node and the root. The pressure term therefore cascades: a department that holds in-flight tasks is pushed back among its siblings, and its organization among the other organizations. The number of sketch entries grows by at most a factor of the number of layers plus one, and the §20 bound $2N/w$ grows by the same factor.

Selection runs at dispatch time, not queue time. A node's share depends on which siblings are backlogged at that moment, and a priority computed at queue time cannot know that. Within a leaf, tasks keep the order of their stored priority (the §7 tag plus pressure). Tasks promoted by `max-queued-time-ms` are served first and charged normally. In hierarchical mode, aging (§10) is ignored.

### Validation

- **`HierarchicalSelectorTest`** (10,000 dispatches, exact to within 0.1%):
    - a 10-department organization against a single-leaf tenant gets 50% / 50%, with each department at 5%;
    - weights organization 3 : tenant 1 and, inside it, department 3 : 1 give 56.25% / 18.75% / 25%;
    - a department returning after 1,000 dispatches of idleness gets 49–51 of the next 100, with no burst;
    - flat mode reproduces the 10/11 problem.
- **`HierarchicalFairnessIntegrationTest`** (PostgreSQL, real priority calculator and dispatcher, 2,000 dispatches each):
    - a hot department with $10\times$ the backlog and its sibling each get exactly 50%, with sliding $\epsilon_{\max}(W=100) = 0$;
    - the 10-department organization against the single-leaf tenant gets 50% / 50%, with $\epsilon_{\max} = 0$;
    - a sibling's first task, arriving behind 500 queued tasks of the hot department, is dispatched in the next tick.

---

## Final Mathematical Model

The current proposed Equalix model is:

$$
\begin{aligned}
E_k(t) &= [F_k(t) < Q_k] \quad (\text{with } Q_k = \infty \Rightarrow E_k = 1) \\
I_k(t) &= p(t) \frac{\hat{F}_k(t)}{w_k} \\
A_x(t) &= \lambda W_x(t) \\
P_x(t) &= T_k(t) + I_k(t) - A_x(t) \\
x^* &= \arg\min_x (P_x, \text{arrival}_x, \text{id}_x) \\
T_k &\leftarrow T_k + \frac{1}{w_k}
\end{aligned}
$$

### Parameter Explanations

- $E_k(t)$ — eligibility/activation status for queue $k$ at time $t$.
- $I_k(t)$ — in-flight pressure, scaled by $p(t)$ and inversely by weight $w_k$.
- $A_x(t)$ — aging credit for task $x$, dependent on aging coefficient $\lambda$ and waiting time $W_x(t)$.
- $P_x(t)$ — final priority score for scheduling.
- $x^*$ — selected task minimizing priority, arrival time, and ID.
- $T_k$ — accumulated virtual scheduling time for key $k$; after dispatching an equal-cost task, $T_k \leftarrow T_k + 1/w_k$.

subject to:

$$
F_k(t) \le Q_k
$$

$$
F_{\text{global}}(t) \le C_{\max}
$$

$$
D(t,t+\Delta t) \le \lceil R(t)\Delta t \rceil
$$

and, under continuously backlogged unconstrained demand:

$$
\limsup_{|W| \to \infty}
\left|
\frac{D_k(W)}{\sum_j D_j(W)}
-
\frac{w_k}{\sum_j w_j}
\right|
\le \epsilon
$$

This is the proposed mathematical foundation for Equalix v0.2.

---

**Revision history:**

- v0.9 – hierarchical virtual time (CFS-style per-layer vruntime with idle floors, per-layer pressure, multi-layer CMS accounting) (EQX-7).
- v0.8 – adaptive RPS stability controls (adjustment interval, latency EMA, direction dampener) evaluated by closed-loop simulation (EQX-6).
- v0.7 – CMS updates applied after commit, 64-bit key hashing (Redis layout v2), startup warm-up; EQX-2 figures refreshed.
- v0.6 – watchdog publishes CMS drift $e_k$ per key and in aggregate before each rebuild (EQX-5).
- v0.5 – measured signed-update CMS error distribution and strict-turnstile bound; validated §17 priority-error bound; identified accounting-fault drift and hash-code collisions (EQX-2).
- v0.4 – configurable aging $A(W)$ (none/linear/log/power) evaluated at dispatch, aged system virtual time, simulation results (EQX-4).
- v0.3 – persistent virtual time $T_k$ implemented (EQX-3); weighted-fairness bound $\epsilon_{\max}(W) \le 2/|W|$ measured (EQX-1).
- v0.2 – clarified notation (leaves $L_k$), added infinite quota semantics, expanded CMS caveat with practical mitigation, added stability as an open question, and aligned the model with the intended persistent virtual time design.
- v0.1 – initial draft.