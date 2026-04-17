---
title: 3. Stored State in EDD Core
description: The stores that make command handling, replay, identities, and deduplication work.
---

EDD Core does not revolve around a single table. It uses a small set of stores,
each with a clear job.

Understanding those jobs makes the runtime much easier to follow.

This is the point in the story where the comforting fantasy of one magical store
gives way to a more useful truth: different kinds of truth need different kinds
of storage.

## The event store

`event_store` is the source of truth.

Each stored event belongs to one aggregate and has an `event-seq` inside that
aggregate stream. That sequence number is what gives the write side ordering and
concurrency protection.

If you want to know what really happened, this is the store that matters most.

Everything else becomes easier to reason about once that hierarchy is clear.

## Aggregate state and aggregate history

EDD Core also keeps derived aggregate state.

- `aggregate_store` holds the latest known aggregate state.
- `aggregate_history` holds stored historical versions of aggregates.

These are not the ultimate truth. They are derived from events. But they are
extremely important in practice because command handling uses them as the base
for reconstruction.

The normal load path is straightforward.

First load the latest snapshot for the aggregate. Then load events after the
snapshot version. Then fold only the missing tail.

That is why the library can feel both current and historical at the same time.

This is one of the details that makes the implementation more grounded than the
usual event-sourcing caricature. It does not choose between speed and history. It
uses both deliberately.

> **Did you know?** An `UPDATE` in PostgreSQL is not actually an in-place edit.
> The engine writes a brand new row version and marks the old one dead, which
> is why heavy update workloads cause table bloat and need `VACUUM`. The "stable
> mutable row" most developers picture does not really exist at the storage
> layer; it is already an append-only log being garbage collected.

## The command store

`command_store` contains commands produced by effects.

The important detail is not only that effects exist. The important detail is
when they are stored. In `edd-core`, they are stored together with the results
of command handling, so the write and the follow-up work stay connected.

That gives the system a built-in transactional-outbox style of behavior.

## The identity store

`identity_store` maps business keys to aggregate ids.

This is how the library handles lookups that are meaningful to the business but
are not the aggregate's primary id. An email address, external reference,
invoice number, or import key can all live here.

This is also one of the places where the library stays simple. A command handler
can return identity records alongside events, and the runtime stores them for
later lookup.

## Command log and response log

Incoming commands are logged, and handled responses are also logged.

These logs are not just for observability. They are part of request handling.

- The command log tells you what entered the system.
- The response log lets the framework return the stored response for a repeated
  `request-id` and breadcrumb path.
- The request-error log records failures during handling.

This is why deduplication in EDD Core is a runtime feature, not just a pattern
left to each application.

## A command request from start to finish

When a command comes in, the runtime does roughly this.

It logs the request, checks whether the same request and breadcrumb path already
have a stored response, loads the aggregate snapshot, loads newer events,
rebuilds the aggregate, runs the command handler, computes the new aggregate,
stores events and effects and identities, stores the response, and returns the
summary.

That flow explains most of the rest of the library.

And more importantly, it gives the book its rhythm: load, decide, store,
rebuild, continue.

## What is derived and what is sacred

The event store is the most important durable record.

The aggregate stores are derived. They are valuable, but rebuildable. The
command store is operationally important, but it exists because of stored
results. The identity store is durable application data because it defines how
business keys resolve.

Keeping those distinctions clear will make the later chapters much easier to
read.
