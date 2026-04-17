---
title: 4. Commands
description: The write-side primitive that decides what should happen to one aggregate.
---

## What a command is

A command is an attempt to make something happen.

In `edd-core`, that means a command is routed to one aggregate, validated,
enriched with any declared dependencies, and then given to a pure handler.

Three properties matter most.

- A command is intent, not fact.
- A command can be rejected.
- A command belongs to one aggregate boundary.

There is something refreshingly modest about that. A command is not a hidden
database mutation. It is not a method call dressed up in bigger clothes. It is
an explicit attempt.

## Shape of a command

Every command says what it wants to do, which aggregate it is about, and which
attributes the decision depends on.

The runtime adds the surrounding execution context such as `request-id`,
`interaction-id`, metadata, user, and breadcrumbs.

Those surrounding fields are not decoration. They are part of why commands are
traceable and deduplicated correctly.

## What the command handler does

A command handler in EDD Core has a narrow job.

- It receives the current aggregate state in `ctx`.
- It receives the incoming command.
- It decides what durable result should come out of that attempt.

Usually that result is one event or a list of events. It may also include
identity mappings. Nil values are ignored, which makes it easy to build results
conditionally.

The handler itself does not write to storage, does not call another service,
and does not dispatch follow-up work directly.

That separation is not ceremony. It is what allows retries, replay, and simple
tests.

This is where a lot of old architecture instincts have to be unlearned. The
handler does less than you first expect, and that is exactly why it ends up
being more trustworthy.

> **Did you know?** A pure command handler is often *faster* in production than
> the equivalent imperative one, not slower. Because it returns plain data and
> touches no I/O, the JIT can inline it aggressively and the runtime can retry
> it on a version conflict without rolling back any side effects. The "purity
> tax" is mostly mythical at the hot path.

## Validation has two layers

The library makes a useful distinction.

- Structural validation belongs at the boundary through schemas.
- Business validation belongs in the handler.

That keeps handlers focused on actual decision making. They do not need to
worry about whether a request forgot a required field. They need to worry about
whether the requested change is valid in the current business state.

## Rejections and errors

One of the most important modeling choices is whether a failed attempt should be
stored as history.

If the business cares that an attempt happened, it is often best to emit a
rejection event. If the problem is purely technical or structural, return an
error instead.

That distinction keeps the event log meaningful.

It also keeps the system emotionally honest. Some failures are part of the story
of the business. Others are just broken requests. Those should not be confused.

## Deterministic ids

Most commands carry the aggregate id directly. Sometimes that id should be
derived instead. EDD Core supports an `id-fn` for that case.

This is useful when the aggregate is a singleton or when the business key
deterministically identifies the stream.

The point is not convenience. The point is that the aggregate must be known
before the handler runs, because the current state must be loaded first.

## Commands work for CRUD too

It is easy to read this pattern and assume it only fits highly specialized
domains. That is not true.

EDD Core works well for CRUD applications if you think about commands at the
right level.

- Create a record.
- Apply a diff.
- Rename a field.
- Archive a record.
- Restore a record.

Those are still commands. They are just simpler commands. The resulting events
can be just as generic if the domain does not need richer language.

This is one of the easiest ways to keep a CRUD system simple while still
preserving immutable history.

That is the quiet lesson many of us learn late: sophistication is not the same
as usefulness. A small clear command is often better than a grand abstraction.

## Why command handlers must stay pure

The runtime may retry command handling when there is a version conflict. That is
safe only if the handler is a pure decision function.

If a handler performed I/O directly, retries would duplicate those actions. EDD
Core avoids that by keeping side effects outside the handler and placing them in
effects instead.

That is why the command chapter must come before the effects chapter. The split
is the design.

## Summary

A command in EDD Core is a pure decision against one aggregate. It can be rich
or simple. It can power a deep workflow or a basic CRUD screen. What matters is
that it makes change explicit, durable, and testable.
