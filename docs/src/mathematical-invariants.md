# Equalix — Mathematical Invariants

**Version:** 0.1  
**Status:** Draft  
**Purpose:** Formal foundation for the Equalix scheduler

---

## 1. System Model

Equalix is a capacity allocator for continuously backlogged multi-tenant workloads.

Let:

- \(K\) — set of fairness keys / tenants.
- \(k \in K\) — one fairness key.
- \(w_k > 0\) — configured weight of key \(k\).
- \(Q_k \in \mathbb{N}\cup\{\infty\}\) — concurrency quota.
- \(F_k(t)\) — authoritative number of in-flight tasks at time \(t\).
- \(\hat F_k(t)\) — approximate in-flight estimate used by the scheduler.
- \(q_k(t)\) — number of queued/eligible tasks.
- \(W_x(t)\) — waiting time of task \(x\).
- \(R(t)\) — global dispatch-rate budget.
- \(C_{\max}\) — global in-flight capacity.
- \(F_{\mathrm{global}}(t)\) — total authoritative in-flight work.
- \(T_k(t)\) — accumulated virtual scheduling time.
- \(p(t)\) — in-flight pressure coefficient.
- \(\lambda\) — aging coefficient.
- \(P_x(t)\) — effective priority of task \(x\).

The fundamental distinction is:

> **Approximate state may influence optimization, but authoritative state defines safety.**

---

# 2. Invariant Hierarchy

Equalix has four conceptual layers:

1. **Safety** — constraints that must never be intentionally violated.
2. **Liveness** — eligible work must eventually receive service.
3. **Fairness** — available capacity should converge toward configured weighted shares.
4. **Optimization** — reduce scheduling latency, contention, executor overload, and fairness error.

The hierarchy is:

\[
\boxed{
\text{Safety} \;>\; \text{Liveness} \;>\; \text{Fairness} \;>\; \text{Optimization}
}
\]

A lower-level objective must never override a higher-level invariant.

---

# 3. Hard Concurrency Quota

For every fairness key \(k\):

\[
\boxed{
F_k(t) \le Q_k
}
\]

for all valid times \(t\).

A task belonging to \(k\) is normally eligible only when:

\[
\boxed{
F_k(t) < Q_k
}
\]

Define quota eligibility:

\[
E_k(t)=
\begin{cases}
1 & F_k(t)<Q_k\\
0 & F_k(t)\ge Q_k
\end{cases}
\]

### Interpretation

Priority answers:

> Which eligible task should run first?

Eligibility answers:

> Is the task allowed to run at all?

Quota is therefore a **hard safety constraint**, not a scheduling preference.

---

# 4. In-Flight Conservation

For each fairness key \(k\):

\[
F_k(t_2)
=
F_k(t_1)
+
S_k(t_1,t_2)
-
T_k(t_1,t_2)
\]

where:

- \(S_k\) — tasks entering the in-flight state.
- \(T_k\) — tasks leaving the in-flight state.

Equivalently:

\[
\boxed{
\Delta F_k
=
\Delta S_k-\Delta T_k
}
\]

A task should enter the in-flight population once and leave it once, subject to explicitly defined retry/reconciliation semantics.

This invariant is the foundation for quota correctness.

---

# 5. Weighted Long-Term Fairness

Let:

\[
D_k(W)
\]

be the number of dispatches for key \(k\) during scheduling window \(W\).

For continuously backlogged keys under unconstrained conditions:

\[
B_k(t)=1
\]

and where quotas, global capacity, executor health, and explicit suspension do not constrain the key, Equalix seeks:

\[
\boxed{
\lim_{|W|\rightarrow\infty}
\frac{D_k(W)}
{\sum_jD_j(W)}
=
\frac{w_k}
{\sum_jw_j}
}
\]

The expected weighted share is:

\[
\boxed{
E_k=
\frac{w_k}
{\sum_jw_j}
}
\]

### Example

For:

\[
w_A=1,\quad w_B=2,\quad w_C=7
\]

the expected shares are:

\[
E_A=10\%
\]

\[
E_B=20\%
\]

\[
E_C=70\%
\]

The guarantee applies to **available capacity under comparable demand**, not to absolute task counts.

---

# 6. Fairness Error

Define the observed share:

\[
S_k(W)=
\frac{D_k(W)}
{\sum_jD_j(W)}
\]

Then define per-key fairness error:

\[
\boxed{
\epsilon_k(W)=|S_k(W)-E_k|
}
\]

System-wide maximum fairness error:

\[
\boxed{
\epsilon_{\max}(W)
=
\max_k\epsilon_k(W)
}
\]

For an implementation with bounded approximation and discrete scheduling effects, a practical invariant is:

\[
\boxed{
\limsup_{|W|\rightarrow\infty}
\epsilon_{\max}(W)
\le\epsilon
}
\]

where \(\epsilon\) is an experimentally established error bound.

The value of \(\epsilon\) should be measured rather than assumed.

---

# 7. Virtual Time

The scheduler should distinguish **historical allocation state** from **current executor pressure**.

Let:

\[
T_k(t)
\]

be the accumulated virtual scheduling position for fairness key \(k\).

For equal-cost tasks, a classical weighted virtual-time update is:

\[
\boxed{
T_k \leftarrow T_k+\frac{1}{w_k}
}
\]

after dispatching one task for \(k\).

For a task with scheduling cost \(s_x\):

\[
\boxed{
T_k \leftarrow T_k+\frac{s_x}{w_k}
}
\]

Higher weights therefore advance virtual time more slowly.

### Interpretation

Virtual time represents:

> How far this key has progressed through its proportional share of scheduling service.

This gives weighted fairness a persistent state rather than deriving fairness only from instantaneous load.

---

# 8. Weighted Priority

Equalix combines accumulated virtual time with current in-flight pressure and task aging.

The base priority is:

\[
\boxed{
P_k^{base}(t)
=
T_k(t)
+
I_k(t)
}
\]

where \(I_k(t)\) is the in-flight pressure defined below.

Lower priority values are selected first.

---

# 9. In-Flight Pressure

Let:

\[
\hat F_k(t)
\]

be the scheduler's approximate in-flight count.

The initial Equalix pressure model is:

\[
\boxed{
I_k(t)
=
p(t)
\frac{\hat F_k(t)}
{w_k}
}
\]

where:

- \(p(t)\) — configurable pressure coefficient.
- \(\hat F_k\) — approximate in-flight count.
- \(w_k\) — tenant weight.

Thus:

\[
\boxed{
P_k^{base}(t)
=
T_k(t)
+
p(t)\frac{\hat F_k(t)}{w_k}
}
\]

### Generalized model

A future implementation may use:

\[
\boxed{
I_k(t)
=
p(t)
\frac{\hat F_k(t)^\alpha}
{w_k^\beta}
}
\]

with:

\[
\alpha,\beta>0
\]

The initial model corresponds to:

\[
\alpha=1,\qquad\beta=1
\]

The generalized form should remain a design parameter rather than an implementation requirement.

---

# 10. Aging / Anti-Starvation

For task \(x\):

\[
W_x(t)=t-arrival_x
\]

where \(W_x\) is the time the task has been waiting.

A linear aging function is:

\[
\boxed{
A_x(t)=\lambda W_x(t)
}
\]

where:

\[
\lambda>0
\]

Since lower priority is better, aging reduces effective priority:

\[
\boxed{
P_x(t)
=
P_k^{base}(t)
-
\lambda W_x(t)
}
\]

Therefore:

\[
\boxed{
P_x(t)
=
T_k(t)
+
p(t)\frac{\hat F_k(t)}{w_k}
-
\lambda W_x(t)
}
\]

As waiting time increases, the task becomes progressively more likely to be selected.

---

# 11. Deriving a Starvation Bound

Suppose task \(x\) has initial priority \(P_0\), and a competing task has priority \(P_c\).

Task \(x\) becomes preferable when:

\[
P_0-\lambda t\le P_c
\]

Therefore:

\[
\lambda t\ge P_0-P_c
\]

and:

\[
\boxed{
t\ge
\frac{P_0-P_c}{\lambda}
}
\]

This provides a direct relationship between:

- initial scheduling disadvantage,
- aging rate,
- maximum waiting time.

The actual starvation bound additionally depends on continuous availability of dispatch capacity and the behavior of other tasks.

---

# 12. Quota Eligibility and Priority Are Separate

The scheduler should conceptually perform:

### Step 1 — eligibility

\[
E_k(t)=
[F_k(t)<Q_k]
\]

### Step 2 — priority

\[
P_x(t)
=
T_k(t)
+
p(t)\frac{\hat F_k(t)}{w_k}
-
\lambda W_x(t)
\]

### Step 3 — selection

Choose the minimum-priority task among eligible tasks.

This separation prevents fairness logic from accidentally overriding hard safety constraints.

---

# 13. Global RPS Capacity

Let:

\[
R(t)
\]

be the current global dispatch rate in tasks/second.

For scheduling interval \(\Delta t\), the rate-derived budget is:

\[
\boxed{
B_R(t,\Delta t)
=
\left\lceil R(t)\Delta t\right\rceil
}
\]

If Equalix also has a global concurrency limit \(C_{\max}\):

\[
F_{\mathrm{global}}(t)
=
\sum_kF_k(t)
\]

and free concurrency is:

\[
\boxed{
B_C(t)
=
C_{\max}-F_{\mathrm{global}}(t)
}
\]

The dispatch budget becomes:

\[
\boxed{
B(t)
=
\max
\left(
0,
\min(B_R,B_C)
\right)
}
\]

Thus:

\[
\boxed{
D(t,t+\Delta t)\le B(t)
}
\]

---

# 14. Capacity Control vs. Fairness Control

Equalix contains two distinct control planes.

### Capacity control

\[
R(t)
\]

determines:

> How much work can be admitted to the executor.

### Fairness control

\[
P_x(t)
\]

determines:

> Which eligible task receives the next unit of capacity.

Therefore:

\[
\boxed{
\text{Adaptive RPS allocates capacity}
}
\]

while:

\[
\boxed{
\text{Virtual scheduling allocates available capacity among tenants}
}
\]

This distinction should remain explicit in the architecture.

---

# 15. Tie-Breaking

If two tasks have equal effective priority:

\[
P_x=P_y
\]

Equalix should use deterministic secondary ordering.

Define:

\[
x\prec y
\]

iff, lexicographically:

\[
(P_x,arrival_x,id_x)
<
(P_y,arrival_y,id_y)
\]

Therefore the ordering is:

1. lowest effective priority,
2. oldest arrival,
3. deterministic task ID.

Formally:

\[
\boxed{
x^*
=
\arg\min_x
(P_x,arrival_x,id_x)
}
\]

This avoids relying on unspecified database ordering.

---

# 16. Approximate In-Flight State

Let:

\[
F_k(t)
\]

be the authoritative count and:

\[
\hat F_k(t)
\]

the approximate scheduler estimate.

Define CMS error:

\[
\boxed{
e_k(t)
=
\hat F_k(t)-F_k(t)
}
\]

Therefore:

\[
\boxed{
\hat F_k(t)=F_k(t)+e_k(t)
}
\]

The scheduler does not assume \(e_k=0\).

---

# 17. Propagation of CMS Error

The pressure term is:

\[
I_k=
p\frac{\hat F_k}{w_k}
\]

Substituting:

\[
\hat F_k=F_k+e_k
\]

gives:

\[
\hat I_k
=
p\frac{F_k+e_k}{w_k}
\]

Therefore:

\[
\boxed{
\hat I_k-I_k
=
p\frac{e_k}{w_k}
}
\]

The resulting priority error is:

\[
\boxed{
\hat P_k-P_k
=
p\frac{e_k}{w_k}
}
\]

assuming virtual time and aging are unchanged.

If:

\[
|e_k|\le E
\]

then:

\[
\boxed{
|\hat P_k-P_k|
\le
p\frac{E}{w_k}
}
\]

This gives a direct way to measure how approximate accounting affects scheduling.

---

# 18. Overestimation vs. Underestimation

### Overestimation

If:

\[
e_k>0
\]

then:

\[
\hat F_k>F_k
\]

and:

\[
\hat P_k>P_k
\]

The key receives excessive scheduling pressure.

Effect:

> Temporary under-allocation of capacity.

This is generally a fairness/performance error rather than a safety violation.

### Underestimation

If:

\[
e_k<0
\]

then:

\[
\hat F_k<F_k
\]

and:

\[
\hat P_k<P_k
\]

The key may receive more scheduling opportunities than its actual load would suggest.

Effect:

> Temporary over-allocation of capacity.

This is why the approximate count must not be the sole source of hard quota enforcement.

---

# 19. Critical CMS Safety Boundary

The fundamental rule is:

\[
\boxed{
\text{CMS} \rightarrow \text{optimization}
}
\]

and:

\[
\boxed{
\text{authoritative state} \rightarrow \text{safety}
}
\]

In particular:

\[
\hat F_k<Q_k
\]

must **not** be interpreted as proof that:

\[
F_k<Q_k
\]

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

# 20. CMS Mathematical Caveat

Classical Count-Min Sketch guarantees are normally stated for non-negative frequency updates.

If Equalix performs both:

\[
+1
\]

and:

\[
-1
\]

updates in the same sketch, classical Count-Min Sketch guarantees should **not automatically be claimed**.

Therefore Equalix should currently define:

\[
e_k(t)=\hat F_k(t)-F_k(t)
\]

and measure its empirical distribution:

- mean error,
- maximum error,
- p95 error,
- p99 error,
- underestimation frequency,
- overestimation frequency.

A formal signed-update error bound should only be introduced after selecting and proving the properties of an appropriate data structure.

---

# 21. Complete Equalix Priority Function

Combining the mechanisms:

\[
\boxed{
P_x(t)
=
T_k(t)
+
p(t)\frac{\hat F_k(t)}{w_k}
-
\lambda W_x(t)
}
\]

where task \(x\) belongs to key \(k\).

The components have distinct responsibilities:

\[
\underbrace{T_k}_{\text{weighted fairness}}
+
\underbrace{
p\frac{\hat F_k}{w_k}
}_{\text{in-flight pressure}}
-
\underbrace{
\lambda W_x
}_{\text{anti-starvation}}
\]

This separation is central to the Equalix model.

---

# 22. Complete Scheduling Algorithm

For each scheduling cycle:

### 1. Determine available capacity

\[
B(t)
=
\max
\left(
0,
\min
\left[
\lceil R(t)\Delta t\rceil,
C_{\max}-F_{\mathrm{global}}(t)
\right]
\right)
\]

### 2. Determine eligible tasks

\[
E_k(t)=[F_k(t)<Q_k]
\]

### 3. Calculate effective priority

\[
P_x(t)
=
T_k(t)
+
p(t)\frac{\hat F_k(t)}{w_k}
-
\lambda W_x(t)
\]

### 4. Select

\[
\boxed{
x^*
=
\arg\min_x
(P_x,arrival_x,id_x)
}
\]

among eligible tasks.

### 5. Dispatch

Dispatch up to \(B(t)\) tasks.

### 6. Update virtual time

For equal-cost tasks:

\[
\boxed{
T_k\leftarrow T_k+\frac1{w_k}
}
\]

### 7. Update authoritative accounting

\[
F_k\leftarrow F_k+1
\]

when a task becomes genuinely in-flight.

Completion decrements the authoritative count according to the task lifecycle semantics.

---

# 23. Core Equalix Invariants

The mathematical model can therefore be summarized by the following invariants.

## Safety

\[
\boxed{
F_k(t)\le Q_k
}
\]

for every key \(k\).

## Global capacity

\[
\boxed{
F_{\mathrm{global}}(t)\le C_{\max}
}
\]

and:

\[
\boxed{
D(t,t+\Delta t)
\le
\lceil R(t)\Delta t\rceil
}
\]

## Liveness

For an eligible task with continuously available dispatch capacity:

\[
\boxed{
W_x(t)\le W_{\max}
}
\]

subject to the configured aging policy and system assumptions.

## Weighted fairness

For continuously backlogged and unconstrained keys:

\[
\boxed{
\limsup_{|W|\to\infty}
\left|
S_k(W)-E_k
\right|
\le\epsilon
}
\]

where:

\[
E_k=
\frac{w_k}{\sum_jw_j}
\]

## Approximation

\[
\boxed{
\hat F_k=F_k+e_k
}
\]

with CMS error affecting optimization but not authoritative safety.

---

# 24. Design Principle

The Equalix scheduler can be summarized as:

\[
\boxed{
\text{Priority}
=
\text{Fairness}
+
\text{Pressure}
-
\text{Aging}
}
\]

more explicitly:

\[
\boxed{
P_x
=
\underbrace{T_k}_{\text{historical weighted allocation}}
+
\underbrace{
p\frac{\hat F_k}{w_k}
}_{\text{current load}}
-
\underbrace{
\lambda W_x
}_{\text{waiting-time compensation}}
}
\]

while:

\[
\boxed{
\text{Eligibility}
=
\text{authoritative quota state}
}
\]

and:

\[
\boxed{
\text{Capacity}
=
\text{adaptive global RPS/concurrency budget}
}
\]

This separation gives each mechanism one clear responsibility.

---

# 25. Open Design Questions

The following should remain explicitly unresolved in v0.1.

### 25.1 Virtual time model

Should Equalix use:

\[
T_k
\]

as persistent accumulated virtual time, or retain the simpler current-time formulation?

### 25.2 Pressure coefficient

How should:

\[
p(t)
\]

relate to executor latency, error rate, and global RPS?

### 25.3 Aging function

Should aging be:

\[
A(W)=\lambda W
\]

or bounded/non-linear?

Potential alternatives:

\[
A(W)=\lambda\log(1+W)
\]

or:

\[
A(W)=\lambda W^\gamma
\]

### 25.4 CMS semantics

What data structure provides useful and defensible error bounds when counters can both increment and decrement?

### 25.5 Fairness window

What constitutes a "sufficiently large" window \(W\)?

This should ultimately be defined experimentally.

### 25.6 Fairness under quota constraints

How should expected weighted shares be calculated when some tenants are continuously quota-constrained?

### 25.7 Adaptive controller stability

What conditions prevent oscillation in:

\[
R(t)
\]

when executor latency and error rates fluctuate?

---

# 26. Intended Evolution

This document should be treated as a mathematical contract under development.

The intended progression is:

\[
\boxed{
\text{Model}
\rightarrow
\text{Simulation}
\rightarrow
\text{Implementation}
\rightarrow
\text{Benchmark}
\rightarrow
\text{Refinement}
}
\]

Before claiming a formal guarantee, Equalix should validate the corresponding invariant through simulation and load testing.

The most important next experiment is to demonstrate weighted fairness:

\[
w_A:w_B:w_C=1:2:7
\]

and measure:

\[
S_A,\ S_B,\ S_C
\]

over increasing scheduling windows.

The second experiment should measure how:

\[
e_k=\hat F_k-F_k
\]

propagates into fairness error.

---

## Final Mathematical Model

The current proposed Equalix model is:

\[
\boxed{
\begin{aligned}
E_k(t)&=[F_k(t)<Q_k]\\[4pt]
I_k(t)&=p(t)\frac{\hat F_k(t)}{w_k}\\[4pt]
A_x(t)&=\lambda W_x(t)\\[4pt]
P_x(t)&=T_k(t)+I_k(t)-A_x(t)\\[4pt]
x^*&=\arg\min_x(P_x,arrival_x,id_x)\\[4pt]
T_k&\leftarrow T_k+\frac1{w_k}
\end{aligned}
}
\]

subject to:

\[
\boxed{
F_k(t)\le Q_k
}
\]

\[
\boxed{
F_{\mathrm{global}}(t)\le C_{\max}
}
\]

\[
\boxed{
D(t,t+\Delta t)\le\lceil R(t)\Delta t\rceil
}
\]

and, under continuously backlogged unconstrained demand:

\[
\boxed{
\limsup_{|W|\to\infty}
\left|
\frac{D_k(W)}{\sum_jD_j(W)}
-
\frac{w_k}{\sum_jw_j}
\right|
\le\epsilon
}
\]

This is the proposed mathematical foundation for Equalix v0.1.
