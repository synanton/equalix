# Introduction

Equalix is an **eventually-fair scheduler** for multi-tenant work. It sits in front of a rate-limited executor and decides *which* task runs next so no fairness key (tenant, client, user) starves the others.

This book is the **developer documentation**: how to run Equalix, call the API, write an executor, and change the code. The long-form design paper is `doc/design.md` in the repository.

## What Equalix does

```text
Producers ──REST / Kafka──► Equalix ──HTTP execute──► Your worker
                                 ▲                         │
                                 └── POST .../complete ◄───┘
```

Scheduling uses **virtual time**:

```text
priority = now_ms + (in_flight_estimate × penalty_factor / weight)
```

Lower priority runs first. Keys with many in-flight tasks are pushed back. Higher `weight` gets a larger share.

In-flight estimates come from a **Count-Min Sketch** (fast path) with a durable `client_counts` table for hard quotas. A **Watchdog** rebuilds both from `DISPATCHED`/`COMMITTED` rows.

## Stack

| Piece | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Persistence | PostgreSQL 14+, Flyway |
| Concurrency | ShedLock + `SELECT … FOR UPDATE SKIP LOCKED` |
| Optional | Kafka ingest, Redis CMS |

## When to use it

Use Equalix when many tenants share a scarce executor and you care about **long-term proportional fairness**, optional per-key quotas, and sequential per-key ordering.

Do not use it as a general broker (Kafka), a workflow engine (Temporal), or when you need exact millisecond fairness at low RPS.

## Current shape (review)

Equalix is a single-module hexagonal service. The core path is production-shaped: ingest → priority → dispatch → complete, with CMS decrement, Watchdog against the task table, sequential bootstrap, API-key auth, and in-flight timeouts.

Still keep in mind:

- Spring Boot **3.5 is past OSS EOL**; plan a 4.x upgrade.
- Local CMS is **per process**. Multi-instance fairness that must be strict should set `app.queue.cms.mode=redis`.
- Kafka is on the classpath and the listener starts unless you disable it; REST-only deploys should turn the listener off or provide brokers.
- There is no admin UI and no dead-letter browser; failed tasks are rows in Postgres.

## How to read this book

1. [Getting started](getting-started.md) — Postgres, run, first task.
2. [Samples](samples/index.md) — scripts and an echo executor under `docs/samples/`.
3. [Developing](developing/codebase.md) — packages, tests, how to add a port.

Build the book locally:

```bash
cd docs
mdbook serve --open
```

Requires [mdBook](https://rust-lang.github.io/mdBook/).
