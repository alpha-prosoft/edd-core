---
title: 10. Identifiers and Tracing
description: Request ids, interaction ids, event sequence numbers, and breadcrumbs in the actual EDD Core runtime.
---

## Traceability is built into the model

EDD Core is unusually good at answering one operational question.

What happened because of this request?

That answer is possible because commands, events, effects, and responses carry a
small set of identifiers consistently.

That consistency is what turns a system from a blur of incidents into a readable
sequence of causes.

## The four identifiers to keep straight

There are four identifiers you should keep separate in your head.

- `request-id` identifies one intent and supports deduplication.
- `interaction-id` ties a whole workflow together.
- `aggregate-id` points to one aggregate stream.
- `event-seq` gives the order inside that stream.

Most operational confusion comes from mixing these up.

## Request id

The `request-id` is the identity of one attempted action. If the same action is
retried, the same request id should be reused.

EDD Core pairs it with breadcrumbs when storing command responses. That is how a
retried step in a workflow can return the already known result instead of
running again.

> **Did you know?** A random UUIDv4 is a poor choice as a primary key on most
> SQL engines. Because the values are unordered, every insert lands in a random
> B-tree leaf, fragmenting the index and trashing cache locality. Time-ordered
> ids like UUIDv7 or ULIDs keep inserts append-friendly and can be several times
> faster on write-heavy tables \u2014 same uniqueness, very different performance
> profile.

## Breadcrumbs

Breadcrumbs describe where in a workflow a command sits.

The root request typically starts at `[0]`. Follow-up commands produced by
effects extend that path. This allows the runtime to distinguish two commands in
the same interaction that might otherwise share the same request id.

Breadcrumbs are also a simple causal trail. They help explain why a particular
step happened.

That is one of the most quietly satisfying ideas in the runtime. A workflow is
not just happening. It is leaving a trail.

## Interaction id

The `interaction-id` is broader than the `request-id`. It ties together all the
commands, events, and follow-up commands that belong to one larger workflow.

This is the id you use when you want the whole story rather than one step.

## Aggregate id and event sequence

The aggregate id answers which stream this is about. The event sequence answers
where inside that stream it happened.

That pair is what gives EDD Core its ordered write behavior and optimistic
concurrency model.

## Metadata keeps the story useful

In addition to ids, the runtime carries metadata such as realm, service names,
user information, and the causal path.

This is why stored events are operationally useful without having to duplicate
their contents into ordinary logs.

## Loop prevention is part of the execution model

Because effects can produce commands which produce events which produce more
effects, the runtime also guards against runaway chains by limiting breadcrumb
depth.

That is one of those small implementation details that says a lot about the
library: it assumes workflows are real and that they need safe boundaries.

## Summary

The runtime uses a small set of identifiers to make retries safe and workflows
readable. Learn those ids once and the behavior of the whole system becomes much
easier to follow.
