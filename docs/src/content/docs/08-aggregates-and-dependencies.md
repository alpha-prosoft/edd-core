---
title: 8. Aggregates and Dependencies
description: Where consistency lives and how handlers receive outside data without losing purity.
---

## Aggregates are where write consistency lives

An aggregate is the boundary within which a command makes a consistent decision.

That is the most useful practical definition because it tells you why the
library rebuilds one aggregate at a time before command handling.

The aggregate is the unit of current-state reconstruction, version checking,
event sequencing, and optimistic retry.

This is one of those ideas that sounds abstract until you try to avoid it. Then
the system teaches it back to you the hard way.

## Why small aggregates are usually better

Large aggregates feel convenient at first because they seem to gather all the
related information into one place. In practice they create contention, heavy
rebuilds, and harder evolution.

EDD Core works best when aggregates are as small as the invariants allow.

That can feel like a disappointment at first, especially if you were raised on
ambitious diagrams. But small aggregates are often where sanity returns.

That does not mean relationships disappear. It means relationships are usually
represented by references and coordinated by commands, queries, and effects.

> **Did you know?** Smaller aggregates usually give *higher* throughput than
> large ones, even though they look like more work. Optimistic concurrency
> retries are scoped to a single aggregate, so a small aggregate has fewer
> writers competing for the same version and almost never collides. The big
> "convenient" aggregate is what becomes the bottleneck under load.

## Reference other aggregates instead of embedding them

If one aggregate needs to know another exists, an id is usually enough.

That keeps ownership clear. One aggregate owns its own history. Another service
or query can enrich the view later if needed.

## Dependencies are reads, not hidden I/O

Sometimes a handler genuinely needs data that lives elsewhere.

EDD Core handles that with declared dependencies. The runtime resolves the
dependency first and adds the result to `ctx` before calling the handler.

That keeps the handler pure. It reads the inputs it was given. It does not reach
out to the world on its own.

Purity here is not aesthetic purity. It is operational kindness to your future
self.

## Local and remote dependencies use the same idea

A dependency may be resolved by a local query in the same service or by a query
sent to another service. The core idea does not change.

The handler still sees ordinary data in context. That uniformity is why tests
remain simple even when production wiring gets more complex.

## Dependencies are for reading, effects are for writing

This is a very important modeling rule.

- If the handler needs information to decide, that is a dependency.
- If a stored fact should cause work elsewhere, that is an effect.

When those two ideas get mixed up, service code becomes much harder to reason
about.

## Singleton aggregates are valid too

Not every aggregate represents a long list of business entities. Sometimes there
is exactly one aggregate of a given kind for the whole service.

EDD Core supports this with deterministic ids through `id-fn`. A global
configuration record, rate set, or singleton workflow state can all be modeled
this way.

## Process aggregates are just aggregates

When a workflow needs its own remembered state, the workflow itself can be an
aggregate. It tracks milestones and emits the next commands as conditions are
met.

That sounds more advanced than it is. It is still the same pattern: commands,
events, aggregate state, and effects.

## Summary

Aggregates define the scope of fresh write decisions. Dependencies let handlers
see the outside data they need without giving up purity. Together they define
where consistency lives and where coordination begins.
