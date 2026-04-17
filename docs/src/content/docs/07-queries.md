---
title: 7. Queries
description: Read-side access, shaped responses, and the difference between public queries and aggregate reconstruction.
---

## Queries answer questions

Queries are the read-side primitive in EDD Core.

Their job is simpler than command handling. A query does not decide. It does not
store facts. It does not trigger effects. It reads some already available state
and returns an answer.

That sounds obvious, but keeping the boundary clean is one of the reasons the
library stays understandable.

Queries are where the story becomes visible to other people. Commands and events
do the hard internal work. Queries are where that work becomes useful.

## Public queries and internal aggregate loads are not the same thing

This distinction matters a lot in this repository.

When a command handler needs current aggregate state, the runtime reconstructs it
from snapshot plus event tail. That is internal write-side machinery.

When an API consumer or another service asks a question, that is a query.

The two can both return current-looking state, but they serve different
purposes.

- Aggregate reconstruction exists so commands can decide correctly.
- Queries exist so callers can read useful shapes.

If those are mixed together, both sides get worse.

Many systems lose clarity here. EDD Core does not need to. It is willing to name
the two concerns separately.

## Queries can read simple or rich views

In the simplest case, a query returns the current aggregate or a portion of it.
In richer cases, it returns a denormalized view shaped for the consumer.

That shaping freedom is important. The read side should not be forced to look
like the write side.

A mobile screen, a back-office dashboard, and a search page may all need
different answers. Queries are where that difference belongs.

> **Did you know?** Adding a denormalised read model usually *reduces* total
> database load instead of increasing it. One pre-shaped read replaces dozens
> of joins per request, and the projection itself is updated once per event \u2014
> not once per reader. Normalisation optimises for write storage;
> denormalisation optimises for the much more frequent read path.

## EDD Core supports both direct and enriched reads

Some queries are entirely local. Others need more data.

EDD Core supports declared query dependencies in the same style as command
dependencies. The framework resolves those dependencies first and injects the
results into `ctx`.

That means a query can stay pure even when it needs extra information from
another query or another service.

## Consumer shape matters more than normalization

One of the practical benefits of separating queries from commands is that query
results can be shaped for the caller instead of for storage purity.

That usually means fewer joins, fewer follow-up calls, and less UI-specific glue
code outside the service.

Because the read side is derived, you can be generous here. If a response shape
is useful, build it.

This is one of the more humane parts of the design. The read side is allowed to
care about the people reading it.

## Eventual consistency is only part of the story

Some read models in an EDD system are eventually consistent. EDD Core supports
that style just fine.

But it would be misleading to stop the explanation there, because the runtime in
this repository also uses current aggregate reconstruction directly. Not every
read path is a lagging projection.

The practical picture is this.

- Consumer-facing read models may lag slightly.
- Command handling still works from the freshest known aggregate state by folding
  stored history.

That distinction lets the system remain honest about consistency without forcing
every read through the same path.

## Query contracts matter

EDD Core lets queries declare what they consume and what they produce.

That is useful not only for validation but also for discipline. It keeps the
read-side API from drifting silently and makes service-to-service reads easier to
trust.

## Summary

Queries are the public read interface of the system. They should answer the
caller's question in the caller's shape. They are separate from aggregate
reconstruction, even when both touch the same underlying history.
