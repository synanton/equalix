# Equalix Tuning Wizard & Sandbox Scripts

**Ticket Type:** Feature / Tooling
**Priority:** Medium-High
**Epic:** Equalix Operational Tooling
**Document Version:** v0.2

------

## Overview

Develop interactive sandbox scripts and a tuning wizard that guides users  through configuring Equalix based on their specific runtime conditions  and SLA requirements. The wizard will collect user-provided workload  characteristics, translate them into mathematical constraints using the  Equalix foundation model, and generate validated configuration files.

------

## Purpose

Equalix has multiple tunable parameters that interact in complex ways. Users  need guided assistance to configure the scheduler appropriately for  their specific workload patterns and SLA targets. This ticket creates a  tool that:

1. Interviews users about runtime conditions (error rates, dispatch delays, concurrency limits, load patterns)
2. Maps SLA requirements to mathematical constraints using the Equalix foundation equations
3. Generates and validates tuned configuration parameters
4. Provides sandbox scripts to test settings before production deployment

------

## Background

The Equalix mathematical foundation establishes the following tunable parameters:

| Parameter            | Symbol   | Role                       | Default            |
| -------------------- | -------- | -------------------------- | ------------------ |
| Weights              | wkwk     | Fairness distribution      | User-defined       |
| Quotas               | QkQk     | Safety caps                | User-defined       |
| Pressure coefficient | p(t)p(t) | In-flight load sensitivity | 1000/R(t)1000/R(t) |
| Aging rate           | λλ       | Anti-starvation speed      | Configurable       |
| Global RPS limit     | R(t)R(t) | Dispatch rate cap          | User-defined       |
| Global concurrency   | Cmax⁡Cmax | In-flight limit            | User-defined       |
| Fairness error bound | ϵϵ       | Max allowed deviation      | Measured           |
| Scheduling interval  | ΔtΔt     | Dispatch cycle             | 100ms              |

The priority function:

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

The capacity budget:

B(t)=max⁡(0,min⁡(⌈R(t)Δt⌉,Cmax⁡−Fglobal(t)))B(t)=max(0,min(⌈R(t)Δt⌉,Cmax−Fglobal(t)))

------

## Functional Requirements

### 1. Interactive Tuning Wizard

Build a CLI-based interactive wizard that:

#### Phase 1: Workload Characterization

| Question                               | Output Variable | Valid Values                                      |
| -------------------------------------- | --------------- | ------------------------------------------------- |
| "How many tenants/keys will you have?" | KK              | Integer ≥ 1                                       |
| "Describe your load pattern"           | `load_pattern`  | `steady` / `bursty` / `diurnal` / `unpredictable` |
| "Average dispatch rate (tasks/sec)?"   | RˉRˉ            | Positive number                                   |
| "Peak dispatch rate (tasks/sec)?"      | Rmax⁡Rmax        | Positive number                                   |
| "Expected task duration (ms)?"         | dˉdˉ            | Positive number                                   |
| "Maximum acceptable queue time (ms)?"  | Wmax⁡Wmax        | Positive number                                   |
| "Desired fairness precision (%)?"      | ϵtargetϵtarget  | 0-100%                                            |

#### Phase 2: SLA Constraints

| Question                                            | Output Variable     | Valid Values                        |
| --------------------------------------------------- | ------------------- | ----------------------------------- |
| "Maximum acceptable error rate (%)?"                | `error_budget`      | 0-100%                              |
| "Maximum dispatch latency (ms)?"                    | `latency_sla`       | Positive number                     |
| "How important is strict fairness vs. low latency?" | `fairness_priority` | `fairness` / `balanced` / `latency` |
| "Should all tenants be equally important?"          | `equal_weights`     | boolean                             |
| "If no, what weight ratio do you want?"             | `weight_ratios`     | Array of integers                   |

#### Phase 3: Operational Constraints

| Question                               | Output Variable      | Valid Values     |
| -------------------------------------- | -------------------- | ---------------- |
| "How many tasks can run concurrently?" | Cmax⁡Cmax             | Positive integer |
| "CPU cores available?"                 | `cpu_cores`          | Positive integer |
| "Memory available (GB)?"               | `memory_gb`          | Positive number  |
| "Is database latency a concern?"       | `db_latency_concern` | boolean          |
| "Expected number of task retries?"     | `retry_rate`         | 0-100%           |

### 2. Mathematical Computation Engine

Implement a calculation module that:

#### Compute Recommended Parameters

| Parameter | Formula/Logic                                                |
| --------- | ------------------------------------------------------------ |
| wkwk      | If equal weights: 1/K1/K; else normalize user-provided ratios |
| QkQk      | Compute as ⌈dˉ×Rmax⁡/K⌉×safety_factor⌈dˉ×Rmax/K⌉×safety_factor |
| p(t)p(t)  | Base: 1000/Rˉ1000/Rˉ; adjust based on priority               |
| λλ        | λ=initial_priority_rangeWmax⁡λ=Wmaxinitial_priority_range     |
| ΔtΔt      | min⁡(100ms,latency_sla/10)min(100ms,latency_sla/10)           |
| ϵϵ        | 0.050.05 for `fairness`, 0.100.10 for `balanced`, 0.200.20 for `latency` |

#### Validate Configuration

Check all invariants:

- □  

  Fk(t)≤QkFk(t)≤Qk under peak load

- □  

  Fglobal(t)≤Cmax⁡Fglobal(t)≤Cmax

- □  

  Wx(t)≤Wmax⁡Wx(t)≤Wmax under normal conditions

- □  

  ϵmax⁡≤ϵtargetϵmax≤ϵtarget is feasible

#### Output Format

Generate three output files:

1. `equalix-config.toml` — Production configuration
2. `equalix-tuning-report.md` — Explanatory document
3. `sandbox-test-plan.sh` — Test script

------

### 3. Sandbox Scripts

Create three testing scripts:

#### a) `sandbox-simulate.sh` — Single-node simulation

bash

```
# Usage
./sandbox-simulate.sh --config equalix-config.toml --duration 300s --rate 100

# What it does:
# - Starts Equalix in sandbox mode (no real tasks)
# - Generates synthetic load with configurable patterns
# - Measures fairness, latency, and error metrics
# - Outputs metrics to CSV for analysis
```



Features:

- Synthetic load generation (Poisson, burst, sinusoidal)
- Real-time metrics display
- Fairness convergence visualization
- Quota enforcement verification

#### b) `sandbox-evaluate.sh` — Configuration comparison

bash

```
# Usage
./sandbox-evaluate.sh --config equalix-config.toml --scenario daily-peak

# What it does:
# - Runs multiple simulation scenarios
# - Compares performance against SLA targets
# - Identifies configuration weaknesses
# - Suggests improvements
```



Features:

- Scenario catalog: `steady`, `burst`, `peak`, `gradual`
- SLA compliance scoring
- Sensitivity analysis
- Recommendation generation

#### c) `sandbox-watchdog.sh` — Runtime monitoring

bash

```
# Usage
./sandbox-watchdog.sh --config equalix-config.toml --threshold 0.95

# What it does:
# - Monitors live Equalix instance
# - Checks fairness error against threshold
# - Detects quota violations
# - Alerts on CMS drift
```



Features:

- Real-time fairness error monitoring
- Quota usage dashboards
- Virtual time drift detection
- CMS error distribution tracking

------

### 4. Tuning Wizard User Interface

#### CLI Implementation (Primary)

python

```
# equalix-tune CLI
$ equalix-tune wizard
Equalix Tuning Wizard v0.1
===========================

Workload Characterization
-------------------------
How many fairness keys/tenants will you have? 5
Describe your load pattern (steady/bursty/diurnal/unpredictable): bursty
Average dispatch rate (tasks/sec): 1000
Peak dispatch rate (tasks/sec): 5000
Expected task duration (ms): 200
Maximum acceptable queue time (ms): 5000
Desired fairness precision (%): 5

SLA Constraints
---------------
Maximum acceptable error rate (%): 0.1
Maximum dispatch latency (ms): 100
Fairness priority (fairness/balanced/latency): balanced

Operational Constraints
-----------------------
How many tasks can run concurrently? 1000
CPU cores available? 8
Memory available (GB): 32
Is database latency a concern? yes
Expected number of task retries (%): 5

Computing recommendations... ✓

[Report]
Weights: [0.2, 0.2, 0.2, 0.2, 0.2]
Quotas: [50, 50, 50, 50, 50]
Pressure coefficient: 0.8 (calculated from load patterns)
Aging rate: 0.0002 (ensures queue time <= 5000ms)
Global RPS: 1000 (peak cap)
Global concurrency: 1000
Scheduling interval: 20ms (latency-optimized)
Expected fairness error: 0.07 (within target)

Write config to equalix-config.toml? (y/n): y
Generate sandbox test script? (y/n): y
Run sandbox evaluation now? (y/n): y
```



#### Web UI (Optional Enhancement)

Provide a browser-based interface with:

- Form-based input collection
- Real-time parameter preview
- Interactive charts showing predicted behavior
- One-click script generation

------

## Mathematical Reference

### Priority Calculation

Px(t)=Tk(t)+p(t)F^k(t)wk−λWx(t)Px(t)=Tk(t)+p(t)wkF^k(t)−λWx(t)

### Tuning Guidance

| User Priority      | p(t)p(t) Strategy                          | λλ Strategy                              | ΔtΔt Strategy                       |
| ------------------ | ------------------------------------------ | ---------------------------------------- | ----------------------------------- |
| **Fairness-first** | Higher sensitivity to keep load balanced   | Lower aging to avoid disrupting fairness | Larger interval for stability       |
| **Latency-first**  | Lower sensitivity to avoid over-penalizing | Higher aging to clear queues quickly     | Smaller interval for responsiveness |
| **Balanced**       | Middle ground per formulas                 | Middle ground per formulas               | Standard 100ms                      |

### Capacity Planning

Cmax⁡≥Rˉ×dˉ×concurrency_factorCmax≥Rˉ×dˉ×concurrency_factor

Where `concurrency_factor` accounts for burstiness and retries:

- Steady load: 1.2
- Bursty: 2.0
- Unpredictable: 3.0

### Quota Sizing

Qk=max⁡(1,⌈Rmax⁡×dˉK×safety_factor⌉)Qk=max(1,⌈KRmax×dˉ×safety_factor⌉)

Where `safety_factor` is derived from user's error tolerance:

| Error Tolerance   | Safety Factor |
| ----------------- | ------------- |
| Critical (<0.01%) | 3.0           |
| High (<0.1%)      | 2.0           |
| Medium (<1%)      | 1.5           |
| Low (>1%)         | 1.2           |

------

## Acceptance Criteria

### Must Have

- ☑  

  Interactive CLI wizard with all questions implemented

- ☑  

  Mathematical computation engine producing valid configurations

- ☑  

  Three sandbox scripts (simulation, evaluation, watchdog)

- ☑  

  Configuration validation against all invariants

- ☑  

  Support for all tunable parameters in foundation document

- ☑  

  JSON/TOML/CSV output formats

- ☑  

  Documentation for all parameters and their relationships

### Should Have

- □  

  Basic UI (Terminal-based with curses or similar)

- □  

  Historical tuning suggestions based on previous runs

- □  

  Integration with Equalix API for live testing

### Could Have

- □  

  Web-based UI with dashboards

- □  

  Automated A/B testing across configurations

- □  

  ML-based recommendation engine for tuning

------

## Technical Implementation Notes

### File Structure

text

```
equalix/
├── scripts/
│   └── tuning/
│       ├── wizard.py
│       ├── engine.py
│       ├── validator.py
│       ├── generator.py
│       ├── sandbox-simulate.sh
│       ├── sandbox-evaluate.sh
│       └── sandbox-watchdog.sh
├── templates/
│   ├── config.toml.template
│   ├── report.md.template
│   └── test-plan.sh.template
├── scenarios/
│   ├── steady.json
│   ├── burst.json
│   ├── peak.json
│   └── gradual.json
└── tests/
    ├── test_wizard.py
    ├── test_engine.py
    └── test_validator.py
```



### Dependencies

- Python 3.9+
- `click` or `argparse` — CLI framework
- `rich` — Beautiful terminal output
- `numpy` — Mathematical operations
- `matplotlib` — Visualization in reports
- `jinja2` — Template generation

### Implementation Phases

1. **Phase 1 (Weeks 1-2)**: CLI wizard and calculation engine
2. **Phase 2 (Week 3)**: Sandbox simulation and evaluation scripts
3. **Phase 3 (Week 4)**: Watchdog script and validation suite
4. **Phase 4 (Week 5)**: Documentation and testing

------

## User Stories

1. As a **SRE**, I want to quickly generate a valid Equalix configuration for my workload without reading the entire foundation document.
2. As a **Platform Engineer**, I want to test configuration changes in a sandbox before deploying to production.
3. As a **Developer**, I want to understand how Equalix parameters affect my workload's performance.
4. As a **Product Owner**, I want to convert SLA requirements into actual scheduler configuration.
5. As a **Support Engineer**, I want to diagnose why fairness is drifting and tune parameters accordingly.

------

## Definition of Done

- □  

  Wizard completes a full interview session and generates valid config

- □  

  All three sandbox scripts run successfully with example configurations

- □  

  Generated configurations pass all mathematical invariant checks

- □  

  Documentation explains every parameter and its tuning considerations

- □  

  At least 5 example scenarios produce reasonable results

- □  

  Unit tests cover calculation engine and validator

------

## References

- Equalix Mathematical Invariants (v0.2) — Sections 3, 7, 9, 10, 13, 21, 23
- Equalix Priority Function — Section 21
- Equalix Capacity Control — Section 13
- Equalix Open Design Questions — Section 25

------

**Author:** Equalix Team
**Date:** 2026-08-16
**Estimated Effort:** 5 weeks
**Priority Impact:** Improves operability and user adoption