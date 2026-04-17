---
title: 5. Events
description: Immutable facts, aggregate streams, and the fold that rebuilds state.
---

## What an event is

An event is a stored fact about something that already happened.

In EDD Core, events are the durable record that command handlers produce and
event handlers consume.

Three characteristics matter.

- It is in the past tense.
- It is immutable once stored.
- It belongs to one aggregate stream.

The event is not a request and not a side effect. It is what the system now
knows to be true.

That sentence is simple enough to miss. It is also the line that keeps the whole
model from dissolving into vague messaging language.

## What the runtime adds

The handler decides the event's domain meaning, but the runtime completes its
stored form.

It assigns the aggregate id if needed, assigns `event-seq`, and stamps request
and interaction information plus metadata. If user context is present, that is
also attached.

This is why events are such strong debugging artifacts. They carry both domain
meaning and execution context.

## Event handlers rebuild the aggregate

An event handler is the fold step that takes the current aggregate and one event
and returns the next aggregate.

That is where current state comes from. Not from hidden mutation, and not from a
special command path. State is what you get after applying events in sequence.

In practice, EDD Core often starts from a stored snapshot and folds only the new
tail of events. The principle is the same.

This is the return of the old transistor-on-the-wall feeling. Complex behavior,
built from one repeated simple move.

> **Did you know?** Clojure's persistent maps and vectors are not copied when
> you "modify" them. They share structure with the previous version through a
> bit-partitioned trie, so producing a new aggregate from an old one and a new
> event is typically O(log32 n) \u2014 effectively constant time at any realistic
> size. Immutability is cheaper than most developers expect, not more
> expensive.

## Purity matters here too

Event handlers must be deterministic. If the same snapshot and the same events
go in, the same aggregate must come out.

Otherwise replay stops being trustworthy.

This is also why event handlers should stay focused on aggregate evolution and
not on external calls.

## Rejections can be events too

One useful aspect of the model is that failed attempts can still become part of
history.

If a rejection matters to the business, storing it as an event keeps the record
honest. The system did not perform the requested action, but something still
happened: an attempt was made and rejected for a reason.

That is often better than making the failure disappear into logs.

The system becomes a place where attempts are remembered, not merely outcomes.

## Events do not write other aggregates directly

An event belongs to one aggregate. If something in another aggregate or another
service should happen because of it, that next step is modeled as an effect that
produces a new command.

This keeps aggregate boundaries clean.

## Event evolution should stay boring

Events live a long time. The safest way to evolve them is usually the least
dramatic one.

- Add fields when the change is additive.
- Introduce a new event type when the meaning changes.
- Keep handlers compatible with older stored shapes.

The important rule is simpler than any migration strategy: do not rewrite the
history that the system already committed to.

There is a lot of maturity hidden in that sentence. The older a system gets, the
more valuable boring evolution becomes.

## Summary

Events are the durable facts of the system. Event handlers are the pure fold
that turns those facts back into current aggregate state. Once that relationship
is clear, effects become straightforward: they are simply what happens after a
fact has been stored.
