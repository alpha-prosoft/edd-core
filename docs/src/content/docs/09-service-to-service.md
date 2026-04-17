---
title: 9. Service-to-Service Communication
description: How services in EDD Core read from each other, send work to each other, and stay traceable.
---

## Two cross-service actions

Across service boundaries, EDD Core keeps the model very small.

- If a service needs data from another service before deciding, it uses a remote
  dependency on a query.
- If a stored fact should cause work in another service, it emits a command to
  that service through an effect.

That is the whole cross-service vocabulary most applications need.

There is relief in that. After years of complex service folklore, it turns out a
lot of communication can be reduced to two clean moves.

## Reads are explicit query dependencies

Remote reads happen before the handler runs. The handler does not perform the
call itself.

This keeps a useful property: by the time the handler executes, all of its
inputs are already present in `ctx`.

That means the handler can be tested without pretending to be an HTTP client.

## Writes are explicit command dispatches

Cross-service writes happen after a fact has been stored. The local service does
not mutate another service directly. It emits a command targeted at that other
service.

This is a very practical design choice. It keeps ownership clear. Each service
still decides its own facts by handling its own commands.

## The runtime model is command oriented

This repository does not primarily describe cross-service integration as
everybody subscribing directly to everybody else's event log. It describes a
system where services interact through queries and commands while each service
stores and reconstructs its own history.

That makes the service contract more explicit and easier to test.

## Failure and retries are expected

Once work crosses a service boundary, retries are normal and duplication must be
assumed.

That is why stable request ids, breadcrumbs, and idempotent handlers matter so
much in this architecture. The runtime is designed for at-least-once behavior on
the command-dispatch side.

The mature version of distributed thinking is not pretending failure is rare. It
is building so that failure does not make the story unreadable.

> **Did you know?** *Exactly-once delivery* between services is provably
> impossible in an asynchronous network with failures \u2014 a result related to
> the FLP impossibility theorem. What real systems actually achieve is
> at-least-once delivery plus idempotent handlers, which together *behave* like
> exactly-once. Anyone selling true exactly-once is selling deduplication.

## Contracts should stay business-facing

Even though services communicate through commands and queries, the payloads are
still part of a contract.

That means names and shapes should stay close to business meaning, not internal
implementation details.

The more these contracts read like business language, the longer they remain
stable.

## Choreography and orchestration both fit

Some workflows emerge naturally from one service producing commands for the
next. Others benefit from a dedicated process aggregate that keeps track of the
workflow itself.

EDD Core can support both approaches because both are still made of the same
pieces: commands, events, aggregate state, and effects.

## Summary

Service-to-service communication in EDD Core comes down to two explicit moves:
query another service before deciding, or send another service a command after a
fact is stored. That simplicity is one of the library's strengths.
