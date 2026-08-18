# Equalix — Mathematical Invariants (Revised)

**Version:** 0.2
**Status:** Draft – incorporates review feedback
**Purpose:** Formal foundation for the Equalix scheduler

------

## 1. System Model

Equalix is a capacity allocator for continuously backlogged multi-tenant workloads.

Let:

- KK — set of fairness keys / tenants.
- k∈Kk∈K — one fairness key.
- wk>0wk>0 — configured weight of key kk.
- Qk∈N∪{∞}Qk∈N∪{∞} — concurrency quota (infinite allowed).
- Fk(t)Fk(t) — authoritative number of in-flight tasks at time tt.
- F^k(t)F^k(t) — approximate in-flight estimate used by the scheduler.
- qk(t)qk(t) — number of queued/eligible tasks.
- Wx(t)Wx(t) — waiting time of task xx.
- R(t)R(t) — global dispatch-rate budget.
- Cmax⁡Cmax — global in-flight capacity.
- Fglobal(t)Fglobal(t) — total authoritative in-flight work.
- Tk(t)Tk(t) — accumulated virtual scheduling time (persistent fairness state).
- p(t)p(t) — in-flight pressure coefficient.
- λλ — aging coefficient.
- Px(t)Px(t) — effective priority of task xx.

The fundamental distinction is:

> **Approximate state may influence optimization, but authoritative state defines safety.**

------

## 2. Invariant Hierarchy

Equalix has four conceptual layers:

1. **Safety** — constraints that must never be intentionally violated.
2. **Liveness** — eligible work must eventually receive service.
3. **Fairness** — available capacity should converge toward configured weighted shares.
4. **Optimization** — reduce scheduling latency, contention, executor overload, and fairness error.

The hierarchy is:

Safety  >  Liveness  >  Fairness  >  OptimizationSafety>Liveness>Fairness>Optimization

A lower-level objective must never override a higher-level invariant.

------

## 3. Hard Concurrency Quota

For every fairness key kk:

Fk(t)≤QkFk(t)≤Qk

for all valid times tt.
If Qk=∞Qk​=∞, the inequality is trivially satisfied and eligibility is always true.

A task belonging to kk is normally eligible only when:

Fk(t)<QkFk(t)<Qk

Define quota eligibility:

Ek(t)={1Fk(t)<Qk0Fk(t)≥QkEk(t)={10Fk(t)<QkFk(t)≥Qk

with the convention that if Qk=∞Qk=∞, then Ek(t)=1Ek(t)=1 for all tt.

### Interpretation

Priority answers:

> Which eligible task should run first?

Eligibility answers:

> Is the task allowed to run at all?

Quota is therefore a **hard safety constraint**, not a scheduling preference.

------

## 4. In-Flight Conservation

For each fairness key kk:

Fk(t2)=Fk(t1)+Sk(t1,t2)−Lk(t1,t2)Fk(t2)=Fk(t1)+Sk(t1,t2)−Lk(t1,t2)

where:

- SkSk — tasks entering the in-flight state.
- LkLk — tasks leaving the in-flight state (replaces earlier TkTk to avoid ambiguity with virtual time).

Equivalently:

ΔFk=ΔSk−ΔLkΔFk=ΔSk−ΔLk

A task should enter the in-flight population once and leave it once,  subject to explicitly defined retry/reconciliation semantics.

This invariant is the foundation for quota correctness.

------

## 5. Weighted Long-Term Fairness

Let:

Dk(W)Dk(W)

be the number of dispatches for key kk during scheduling window WW.

For continuously backlogged keys under unconstrained conditions:

Bk(t)=1Bk(t)=1

and where quotas, global capacity, executor health, and explicit suspension do not constrain the key, Equalix seeks:

lim⁡∣W∣→∞Dk(W)∑jDj(W)=wk∑jwj∣W∣→∞lim∑jDj(W)Dk(W)=∑jwjwk

The expected weighted share is:

Ek=wk∑jwjEk=∑jwjwk

### Example

For:

wA=1,wB=2,wC=7wA=1,wB=2,wC=7

the expected shares are:

EA=10%,EB=20%,EC=70%EA=10%,EB=20%,EC=70%

The guarantee applies to **available capacity under comparable demand**, not to absolute task counts.

------

## 6. Fairness Error

Define the observed share:

Sk(W)=Dk(W)∑jDj(W)Sk(W)=∑jDj(W)Dk(W)

Then define per-key fairness error:

ϵk(W)=∣Sk(W)−Ek∣ϵk(W)=∣Sk(W)−Ek∣

System-wide maximum fairness error:

ϵmax⁡(W)=max⁡kϵk(W)ϵmax(W)=kmaxϵk(W)

For an implementation with bounded approximation and discrete scheduling effects, a practical invariant is:

lim sup⁡∣W∣→∞ϵmax⁡(W)≤ϵ∣W∣→∞limsupϵmax(W)≤ϵ

where ϵϵ is an experimentally established error bound.
The value of ϵϵ should be measured rather than assumed.

------

## 7. Virtual Time (Persistent Fairness State)

The scheduler distinguishes **historical allocation state** from **current executor pressure**.

Let:

Tk(t)Tk(t)

be the accumulated virtual scheduling position for fairness key kk.
This is a persistent value that survives across scheduling cycles.

For equal-cost tasks, a classical weighted virtual-time update is:

Tk←Tk+1wkTk←Tk+wk1

after dispatching one task for kk.

For a task with scheduling cost sxsx:

Tk←Tk+sxwkTk←Tk+wksx

Higher weights therefore advance virtual time more slowly.

### Interpretation

Virtual time represents:

> How far this key has progressed through its proportional share of scheduling service.

This gives weighted fairness a persistent state rather than deriving fairness only from instantaneous load.

> **Note on implementation:** The current Equalix implementation uses a simpler “current time”  approach, but the intended long‑term model is based on persistent TkTk. Future versions may migrate to this model to improve fairness guarantees.

------

## 8. Weighted Priority

Equalix combines accumulated virtual time with current in-flight pressure and task aging.

The base priority is:

Pkbase(t)=Tk(t)+Ik(t)Pkbase(t)=Tk(t)+Ik(t)

where Ik(t)Ik(t) is the in-flight pressure defined below.

Lower priority values are selected first.

------

## 9. In-Flight Pressure

Let:

F^k(t)F^k(t)

be the scheduler's approximate in-flight count.

The initial Equalix pressure model is:

Ik(t)=p(t)F^k(t)wkIk(t)=p(t)wkF^k(t)

where:

- p(t)p(t) — configurable pressure coefficient.
- F^kF^k — approximate in-flight count.
- wkwk — tenant weight.

Thus:

Pkbase(t)=Tk(t)+p(t)F^k(t)wkPkbase(t)=Tk(t)+p(t)wkF^k(t)

### Generalized model

A future implementation may use:

Ik(t)=p(t)F^k(t)αwkβIk(t)=p(t)wkβF^k(t)α

with:

α,β>0α,β>0

The initial model corresponds to:

α=1,β=1α=1,β=1

The generalized form should remain a design parameter rather than an implementation requirement.

------

## 10. Aging / Anti-Starvation

For task xx:

Wx(t)=t−arrivalxWx(t)=t−arrivalx

where WxWx is the time the task has been waiting.

A linear aging function is:

Ax(t)=λWx(t)Ax(t)=λWx(t)

where:

λ>0λ>0

Since lower priority is better, aging reduces effective priority:

Px(t)=Pkbase(t)−λWx(t)Px(t)=Pkbase(t)−λWx(t)

Therefore:

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

As waiting time increases, the task becomes progressively more likely to be selected.

------

## 11. Deriving a Starvation Bound

Suppose task xx has initial priority P0P0, and a competing task has priority PcPc.

Task xx becomes preferable when:

P0−λt≤PcP0−λt≤Pc

Therefore:

λt≥P0−Pcλt≥P0−Pc

and:

t≥P0−Pcλt≥λP0−Pc

This provides a direct relationship between:

- initial scheduling disadvantage,
- aging rate,
- maximum waiting time.

The actual starvation bound additionally depends on continuous availability of dispatch capacity and the behavior of other tasks.

------

## 12. Quota Eligibility and Priority Are Separate

The scheduler should conceptually perform:

### Step 1 — eligibility

Ek(t)=[Fk(t)<Qk]Ek(t)=[Fk(t)<Qk]

(if Qk=∞Qk=∞, always true)

### Step 2 — priority

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

### Step 3 — selection

Choose the minimum-priority task among eligible tasks.

This separation prevents fairness logic from accidentally overriding hard safety constraints.

------

## 13. Global RPS Capacity

Let:

R(t)R(t)

be the current global dispatch rate in tasks/second.

For scheduling interval ΔtΔt, the rate-derived budget is:

BR(t,Δt)=⌈R(t)Δt⌉BR(t,Δt)=⌈R(t)Δt⌉

If Equalix also has a global concurrency limit Cmax⁡Cmax:

Fglobal(t)=∑kFk(t)Fglobal(t)=k∑Fk(t)

and free concurrency is:

BC(t)=Cmax⁡−Fglobal(t)BC(t)=Cmax−Fglobal(t)

The dispatch budget becomes:

B(t)=max⁡(0,min⁡(BR,BC))B(t)=max(0,min(BR,BC))

Thus:

D(t,t+Δt)≤B(t)D(t,t+Δt)≤B(t)

------

## 14. Capacity Control vs. Fairness Control

Equalix contains two distinct control planes.

### Capacity control

R(t)R(t)

determines:

> How much work can be admitted to the executor.

### Fairness control

Px(t)Px(t)

determines:

> Which eligible task receives the next unit of capacity.

Therefore:

Adaptive RPS allocates capacityAdaptive RPS allocates capacity

while:

Virtual scheduling allocates available capacity among tenantsVirtual scheduling allocates available capacity among tenants

This distinction should remain explicit in the architecture.

------

## 15. Tie-Breaking

If two tasks have equal effective priority:

Px=PyPx=Py

Equalix should use deterministic secondary ordering.

Define:

x≺yx≺y

iff, lexicographically:

(Px,arrivalx,idx)<(Py,arrivaly,idy)(Px,arrivalx,idx)<(Py,arrivaly,idy)

Therefore the ordering is:

1. lowest effective priority,
2. oldest arrival,
3. deterministic task ID.

Formally:

x∗=arg⁡min⁡x(Px,arrivalx,idx)x∗=argxmin(Px,arrivalx,idx)

This avoids relying on unspecified database ordering.

------

## 16. Approximate In-Flight State

Let:

Fk(t)Fk(t)

be the authoritative count and:

F^k(t)F^k(t)

the approximate scheduler estimate.

Define CMS error:

ek(t)=F^k(t)−Fk(t)ek(t)=F^k(t)−Fk(t)

Therefore:

F^k(t)=Fk(t)+ek(t)F^k(t)=Fk(t)+ek(t)

The scheduler does not assume ek=0ek=0.

------

## 17. Propagation of CMS Error

The pressure term is:

Ik=pF^kwkIk=pwkF^k

Substituting:

F^k=Fk+ekF^k=Fk+ek

gives:

I^k=pFk+ekwkI^k=pwkFk+ek

Therefore:

I^k−Ik=pekwkI^k−Ik=pwkek

The resulting priority error is:

P^k−Pk=pekwkP^k−Pk=pwkek

assuming virtual time and aging are unchanged.

If:

∣ek∣≤E∣ek∣≤E

then:

∣P^k−Pk∣≤pEwk∣P^k−Pk∣≤pwkE

This gives a direct way to measure how approximate accounting affects scheduling.

------

## 18. Overestimation vs. Underestimation

### Overestimation

If:

ek>0ek>0

then:

F^k>FkF^k>Fk

and:

P^k>PkP^k>Pk

The key receives excessive scheduling pressure.

Effect:

> Temporary under-allocation of capacity.

This is generally a fairness/performance error rather than a safety violation.

### Underestimation

If:

ek<0ek<0

then:

F^k<FkF^k<Fk

and:

P^k<PkP^k<Pk

The key may receive more scheduling opportunities than its actual load would suggest.

Effect:

> Temporary over-allocation of capacity.

This is why the approximate count must not be the sole source of hard quota enforcement.

------

## 19. Critical CMS Safety Boundary

The fundamental rule is:

CMS→optimizationCMS→optimization

and:

authoritative state→safetyauthoritative state→safety

In particular:

F^k<QkF^k<Qk

must **not** be interpreted as proof that:

Fk<QkFk<Qk

Instead, quota enforcement must ultimately rely on authoritative state.

CMS error may therefore affect:

- scheduling order,
- fairness precision,
- temporary load distribution,

but must not intentionally invalidate:

- hard concurrency limits,
- durable state transitions,
- accounting invariants.

------

## 20. CMS Mathematical Caveat and Practical Approach

Classical Count-Min Sketch guarantees are normally stated for non-negative frequency updates.

If Equalix performs both:

+1+1

and:

−1−1

updates in the same sketch, classical Count-Min Sketch guarantees should **not automatically be claimed**.

Therefore Equalix should currently define:

ek(t)=F^k(t)−Fk(t)ek(t)=F^k(t)−Fk(t)

and measure its empirical distribution:

- mean error,
- maximum error,
- p95 error,
- p99 error,
- underestimation frequency,
- overestimation frequency.

A formal signed-update error bound should only be introduced after  selecting and proving the properties of an appropriate data structure.

In the interim, the system employs a **Watchdog** that periodically reconstructs the approximate counts from the  authoritative task table, bounding drift. This pragmatic approach  maintains safety while empirical data on error distributions is  collected.

------

## 21. Complete Equalix Priority Function

Combining the mechanisms:

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

where task xx belongs to key kk.

The components have distinct responsibilities:

Tk⏟weighted fairness+pF^kwk⏟in-flight pressure−λWx⏟anti-starvationweighted fairnessTk+in-flight pressurepwkF^k−anti-starvationλWx

This separation is central to the Equalix model.

------

## 22. Complete Scheduling Algorithm

For each scheduling cycle:

### 1. Determine available capacity

B(t)=max⁡(0,min⁡[⌈R(t)Δt⌉,Cmax⁡−Fglobal(t)])B(t)=max(0,min[⌈R(t)Δt⌉,Cmax−Fglobal(t)])

### 2. Determine eligible tasks

Ek(t)=[Fk(t)<Qk]Ek(t)=[Fk(t)<Qk]

(with Qk=∞Qk=∞ always eligible)

### 3. Calculate effective priority

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

### 4. Select

x∗=arg⁡min⁡x(Px,arrivalx,idx)x∗=argxmin(Px,arrivalx,idx)

among eligible tasks.

### 5. Dispatch

Dispatch up to B(t)B(t) tasks.

### 6. Update virtual time

For equal-cost tasks:

Tk←Tk+1wkTk←Tk+wk1

### 7. Update authoritative accounting

Fk←Fk+1Fk←Fk+1

when a task becomes genuinely in-flight.

Completion decrements the authoritative count according to the task lifecycle semantics.

------

## 23. Core Equalix Invariants

The mathematical model can therefore be summarized by the following invariants.

## Safety

Fk(t)≤QkFk(t)≤Qk

for every key kk.

## Global capacity

Fglobal(t)≤Cmax⁡Fglobal(t)≤Cmax

and:

D(t,t+Δt)≤⌈R(t)Δt⌉D(t,t+Δt)≤⌈R(t)Δt⌉

## Liveness

For an eligible task with continuously available dispatch capacity:

Wx(t)≤Wmax⁡Wx(t)≤Wmax

subject to the configured aging policy and system assumptions.

## Weighted fairness

For continuously backlogged and unconstrained keys:

lim sup⁡∣W∣→∞∣Sk(W)−Ek∣≤ϵ∣W∣→∞limsup∣Sk(W)−Ek∣≤ϵ

where:

Ek=wk∑jwjEk=∑jwjwk

## Approximation

F^k=Fk+ekF^k=Fk+ek

with CMS error affecting optimization but not authoritative safety.

------

## 24. Design Principle

The Equalix scheduler can be summarized as:

Priority=Fairness+Pressure−AgingPriority=Fairness+Pressure−Aging

more explicitly:

Px=Tk⏟historical weighted allocation+pF^kwk⏟current load−λWx⏟waiting-time compensationPx=historical weighted allocationTk+current loadpwkF^k−waiting-time compensationλWx

while:

Eligibility=authoritative quota stateEligibility=authoritative quota state

and:

Capacity=adaptive global RPS/concurrency budgetCapacity=adaptive global RPS/concurrency budget

This separation gives each mechanism one clear responsibility.

------

## 25. Open Design Questions

The following remain explicitly unresolved in v0.2. Some are addressed with interim strategies.

### 25.1 Virtual time model

Should Equalix use:

TkTk

as persistent accumulated virtual time, or adopt the simpler current-time formulation?

**Current stance:** The mathematical model uses persistent TkTk; the implementation may use a simplification. Future work will evaluate the benefits of full persistence.

### 25.2 Pressure coefficient

How should:

p(t)p(t)

relate to executor latency, error rate, and global RPS?

**Interim:** p(t)=1000/R(t)p(t)=1000/R(t) is used; stability analysis and adaptive tuning are ongoing.

### 25.3 Aging function

Should aging be:

A(W)=λWA(W)=λW

or bounded/non-linear?

Potential alternatives:

A(W)=λlog⁡(1+W)A(W)=λlog(1+W)

or:

A(W)=λWγA(W)=λWγ

**Current:** Linear aging is used; non-linear forms are under investigation to improve response to long waits.

### 25.4 CMS semantics

What data structure provides useful and defensible error bounds when counters can both increment and decrement?

**Interim:** Empirical error measurement + Watchdog reconstruction. Formal analysis is deferred.

### 25.5 Fairness window

What constitutes a "sufficiently large" window WW?

**Plan:** This will be determined experimentally via simulation and production benchmarks.

### 25.6 Fairness under quota constraints

How should expected weighted shares be calculated when some tenants are continuously quota-constrained?

**Note:** The guarantee only applies when keys are not quota‑limited. Future work may extend the model to incorporate quota pressure.

### 25.7 Adaptive controller stability

What conditions prevent oscillation in:

R(t)R(t)

when executor latency and error rates fluctuate?

**Interim:** Hysteresis and smoothing are applied; formal stability analysis is a future task.

------

## 26. Intended Evolution

This document should be treated as a mathematical contract under development.

The intended progression is:

Model→Simulation→Implementation→Benchmark→RefinementModel→Simulation→Implementation→Benchmark→Refinement

Before claiming a formal guarantee, Equalix should validate the corresponding invariant through simulation and load testing.

The most important next experiment is to demonstrate weighted fairness:

wA:wB:wC=1:2:7wA:wB:wC=1:2:7

and measure:

SA, SB, SCSA, SB, SC

over increasing scheduling windows.

The second experiment should measure how:

ek=F^k−Fkek=F^k−Fk

propagates into fairness error.

A third experiment should evaluate the stability of the adaptive RPS controller under varying load.

------

## Final Mathematical Model

The current proposed Equalix model is:

Ek(t)=[Fk(t)<Qk](with Qk=∞⇒Ek=1)Ik(t)=p(t)F^k(t)wkAx(t)=λWx(t)Px(t)=Tk(t)+Ik(t)−Ax(t)x∗=arg⁡min⁡x(Px,arrivalx,idx)Tk←Tk+1wkEk(t)Ik(t)Ax(t)Px(t)x∗Tk=[Fk(t)<Qk](with Qk=∞⇒Ek=1)=p(t)wkF^k(t)=λWx(t)=Tk(t)+Ik(t)−Ax(t)=argxmin(Px,arrivalx,idx)←Tk+wk1

subject to:

Fk(t)≤QkFk(t)≤QkFglobal(t)≤Cmax⁡Fglobal(t)≤CmaxD(t,t+Δt)≤⌈R(t)Δt⌉D(t,t+Δt)≤⌈R(t)Δt⌉

and, under continuously backlogged unconstrained demand:

lim sup⁡∣W∣→∞∣Dk(W)∑jDj(W)−wk∑jwj∣≤ϵ∣W∣→∞limsup∑jDj(W)Dk(W)−∑jwjwk≤ϵ

This is the proposed mathematical foundation for Equalix v0.2.

------

**Revision history:**

- v0.1 – initial draft.
- v0.2 – clarified notation (leaves LkLk), added infinite quota semantics, expanded CMS caveat with practical  mitigation, added stability as an open question, and aligned the model  with the intended persistent virtual time design.