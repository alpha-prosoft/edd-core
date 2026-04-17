---
title: 2. Immutable State as History
description: Why EDD Core stores facts first and derives current state from them.
---

## The idea underneath the library

The heart of EDD Core is simple.

Instead of treating the latest row as the whole truth, it treats durable facts
as the primary record and current state as something that can be rebuilt.

That is the immutable move the library is built around. It gives you a better
answer to the question, "How did we get here?"

That question matters more than it looks. A great deal of software pain comes
from systems that can still tell you what they are, but can no longer tell you
how they became that way.

## Current state still matters

This does not mean current state disappears. It means current state stops being
the only thing that matters.

In `edd-core`, the latest aggregate is still important. Command handlers need it
to make decisions. Queries often need it to answer quickly. Snapshots and
aggregate views exist exactly for that reason.

The difference is that current state is not mysterious. It is derived from a
known history, and that history can be rebuilt, rechecked, or replayed.

That is the emotional center of the whole approach. We do not abandon current
state. We stop worshipping it.

## Why immutable history is useful in normal product work

Immutable history sounds abstract until you need to answer ordinary questions.

- Why was this record archived?
- Which action changed this field?
- Was this retry already handled?
- What did the system believe just before the failure?
- What exactly happened in this workflow across services?

Mutable tables tend to answer those questions indirectly. You look at audit
tables, loose logs, maybe a timestamp column, maybe a status column whose
meaning drifted over time.

EDD Core makes the answer part of the main model.

That is when immutability stops sounding academic and starts feeling practical.

> **Did you know?** Storing every fact does not necessarily mean using more
> disk than a mutable table. A row that is updated a thousand times in Postgres
> produces a thousand dead tuples until `VACUUM` runs, and write-ahead logs
> already record each change. An event log just exposes that history as a
> first-class citizen instead of throwing it away.

## Replay is a practical tool, not a philosophical claim

When state is derived from history, replay stops being an exotic idea. It
becomes an engineering tool.

- If a projection is wrong, rebuild it.
- If a bug depends on a particular event sequence, reproduce it.
- If a new read model is needed, derive it from what already happened.
- If current state must be rechecked, fold the durable facts again.

EDD Core uses this directly when it rebuilds aggregates from snapshot plus event
tail. Replay is not only for rare recovery work. It is part of normal command
execution.

This is one reason the library feels lower level than many architectural
presentations of event sourcing. It is not talking about replay as a special
ceremony. It is using the same idea every day.

## Immutability changes how you model change

Once you stop overwriting state directly, a useful modeling discipline appears.

You no longer ask only, "What should the row look like now?" You also ask,
"What happened?" Those are not the same question.

For example, in a CRUD style application, a user editing a record does not have
to mean "replace everything in place." It can mean "a diff was applied," or
"these fields changed," or "this record was archived." The current record still
looks like a normal object. The path to that object is no longer lost.

That is what makes immutable programming useful here. The change itself becomes
data.

## Why this makes handlers easier to trust

Handlers are easier to trust when they do one thing clearly.

- A command handler decides which facts should be recorded.
- An event handler describes how those facts change aggregate state.
- An effect declares what should happen next.
- A query reads a shaped view.

Each part becomes smaller because it has a narrower job. That is one of the
main reasons the library feels testable in practice.

## The trade is discipline

Immutable systems are not free. They ask for more explicit thinking.

- Event names matter.
- Aggregate boundaries matter.
- Version compatibility matters.
- Read paths must be designed deliberately.

But the payoff is large. The behavior of the system becomes easier to inspect,
easier to test, and easier to replay.

The whole rest of the book is really about this trade: a little more explicit
modeling in exchange for much less hidden behavior.

## What to keep in mind for the rest of the book

The rest of the book builds on one idea.

Current state is important, but current state is not sacred. History is the
authoritative record. Aggregates, views, and responses are derived from it.

Once that is clear, the rest of `edd-core` starts to make sense.
