---
title: 6. Effects
description: How stored facts produce follow-up commands without putting I/O inside handlers.
---

## Why effects exist

Commands decide. Events record. But real systems also need follow-up work.

Something should happen because an event was stored, but that follow-up should
not happen inside the command handler itself.

That is the role of effects.

This is the chapter where many architectures usually become theatrical. They
start talking about choreography, buses, delivery semantics, and systems talking
to systems as if the real goal were to sound distributed.

EDD Core takes the opposite path. First store the fact. Then declare the next
command. Keep it plain.

## What an effect returns

An effect in EDD Core returns commands to be placed into the command store after
the original result is stored.

Those commands may target the same service or another service. The shape varies
slightly, but the idea is the same. The effect is not performing the work. It
is declaring the next work item.

This is one of the cleanest parts of the design.

## Why this split is important

If a command handler made network calls directly, several bad things would
happen.

- Retries could repeat the side effect.
- Failures could leave the outside world changed without a durable fact.
- Tests would become much heavier.

Effects avoid all three problems by turning follow-up work into stored commands.

Once you see that clearly, a lot of "enterprise integration" starts to look like
needless excitement around a very simple idea.

> **Did you know?** A "transactional outbox" does not need a distributed
> transaction or two-phase commit. Writing the follow-up command into the same
> database transaction as the events is enough, because a separate dispatcher
> can read it later and retry safely. Most of the hard parts of distributed
> messaging disappear once the next step is just another row.

## Same-service and cross-service work

Effects can drive local workflows inside the same service. They can also send
commands to another service.

That distinction is operationally important, but conceptually simple. In both
cases the effect returns commands. In both cases the commands are stored first.
In both cases dispatch is handled by the runtime rather than by your business
logic.

## Service boundaries in this runtime

This repository is not built around services subscribing directly to each
other's event stores. Cross-service coordination happens mainly through commands
and queries.

That is worth stating plainly because many event-sourcing explanations assume a
message bus first. EDD Core takes a more concrete approach.

- Reads across services are expressed as dependencies on queries.
- Writes across services are expressed as effects that emit commands.

That model keeps write decisions explicit and keeps service boundaries visible.

## Effects are still pure functions

An effect handler is still just a function from context and event to data.

That means it stays easy to test. Given an event, you can assert which commands
should be queued next.

This is one of the reasons EDD Core workflows remain understandable even when
they span many steps.

## Idempotency still matters

Because effect dispatch can be retried, effect-produced commands should be
designed so duplicate delivery is safe.

In practice that often means using stable request identifiers, stable aggregate
ids, or other deterministic routing values rather than inventing a fresh meaning
on every retry.

The runtime already gives you the deduplication mechanisms. The application has
to use them deliberately.

## Compensation is another forward action

Distributed workflows cannot be rolled back like one database transaction. When
something later fails, the answer is another command and another event that
records the correction.

That is an important mindset. A refund, release, or cancellation is not a magic
undo. It is new history.

That point tends to separate systems that merely react from systems that can be
understood later.

## Summary

Effects are the bridge from stored facts to next actions. They let EDD Core keep
command handlers pure while still supporting rich local workflows and
cross-service coordination.
