---
title: 1. What EDD Core Is
description: A low-level library for immutable application design in Clojure.
---

## Start with the simplest accurate description

EDD Core is a low-level library for building applications around immutable
facts.

That sentence matters because it keeps expectations honest.

- It is a library, not a platform.
- It is low level, not magical.
- It is about immutable business history, not just messaging.
- It is usable for large workflows and for ordinary CRUD applications.

That kind of sentence usually only becomes attractive after disappointment.

Most developers do not begin by wanting a smaller library and fewer illusions.
Most begin by wanting the perfect architecture. A complete one. A reassuring
one. A structure large enough to explain everything.

This chapter begins where many of those stories end: after the fascination with
tiers, framework stacks, hidden runtime cleverness, and diagrams that look more
finished than the systems they describe.

If you open the implementation, that is exactly what you see. The core API is
small. You register commands, events, effects, and queries. The framework loads
the current aggregate, runs pure handlers, stores events and follow-up commands,
and makes the whole flow traceable.

> **Did you know?** A "low-level" library is often easier to keep stable than a
> high-level framework. The smaller the surface area, the fewer assumptions a
> caller can accidentally rely on, so backward compatibility becomes cheaper.
> Frameworks tend to break on upgrades not because they do too little, but
> because they expose too much.

## The problem it solves

Many systems store only the latest mutable row. That is often convenient, but
it throws away the story of how the state came to be.

Once the new value is written, the old reasoning is gone. Support asks what
happened. Product asks why a record changed twice. Finance asks which request
caused a correction. Engineering tries to reconstruct the path from logs,
timestamps, and guesswork.

EDD Core solves that by treating state changes as durable facts. It gives you a
place to record what happened, a deterministic way to rebuild current state,
and a clean split between deciding, storing, reacting, and reading.

That answer is surprisingly modest. It does not promise the end of complexity.
It promises a better place to stand when complexity arrives.

## Why this works especially well in Clojure

Clojure is already built around immutable data and pure transformation. EDD
Core fits that style naturally.

A command handler does not need hidden mutable state. It receives a context and
a command, decides what should happen, and returns data. An event handler folds
data into new data. An effect declares follow-up commands as data. A query reads
data and returns data.

That has a practical consequence. Tests become simple because the moving parts
are explicit. Most of the business logic can be checked with plain maps and
expected results instead of elaborate mocking.

## It is not only for "big architecture"

This is one of the easiest things to misunderstand about the library.

EDD Core does support serious event-sourced systems, cross-service workflows,
and rich audit trails. But that does not mean it is reserved for large or
dramatic domains.

It can also be a very good fit for CRUD applications.

If a CRUD application benefits from immutable history, explicit write logic, and
cheap tests, then it is a valid EDD Core application. In many cases the command
layer can stay generic and simple, such as "create record", "apply diff",
"rename", "archive", or "restore". The point is not to force artificial
complexity. The point is to keep change explicit and durable.

That is an important turn in the story. The goal is not to make software feel
more sophisticated. The goal is to make it more honest.

## What the library actually gives you

At its core, EDD Core gives you six building blocks.

- Commands express intent.
- Events record facts.
- Event handlers rebuild aggregate state.
- Effects queue new commands after facts are stored.
- Queries answer read-side questions.
- Identities map business keys to aggregate ids.

Around those building blocks the runtime also provides important operational
pieces: request logging, response caching for deduplication, breadcrumbs,
interaction tracing, snapshots, history, and command dispatch.

## What it deliberately does not hide

EDD Core is intentionally explicit about aggregate reconstruction.

When a command is handled, the system does not pretend that current state came
from nowhere. It loads the latest stored aggregate snapshot if one exists, loads
events after that version, folds them, and gives your handler the current
aggregate.

That is why the library can support both of these statements at the same time:

- command decisions can be made against fresh aggregate state,
- some consumer read models can still be eventually consistent.

That combination is one of the key ideas in this repository.

In other words, the book is not moving toward a grand reveal where everything is
eventually consistent and everyone just learns to live with it. The actual
runtime is more practical than that. It rebuilds what it needs in order to make
good decisions.

## What kind of teams tend to like it

- Teams that want a clear audit trail.
- Teams that want business logic to be explicit and pure.
- Teams that need reliable command chaining across services.
- Teams that want better tests without building a giant mock universe.
- Teams that are comfortable owning a little more model clarity in exchange for
  much less hidden behavior.

## The shape of the rest of the book

The rest of the book moves from first principles to the actual runtime shape of
`edd-core`.

- Chapter 2 explains immutable state and why history is the center.
- Chapter 3 explains the stored pieces that make the runtime work.
- Chapters 4 to 7 cover the four main primitives.
- Chapters 8 to 10 explain consistency, service boundaries, and tracing.
- Chapter 11 explains why testing becomes unusually straightforward.

Read it as a guide to the codebase, not as a theory textbook.

It is a book about ending up somewhere simpler than where you started.
