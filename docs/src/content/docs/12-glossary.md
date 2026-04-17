---
title: 12. Glossary
description: Terms used throughout the book.
---

### Aggregate

The smallest unit of state that must be decided consistently during command
handling. Owns an event stream. Rebuilt from snapshot plus newer events.

### Aggregate history

Stored historical aggregate versions. Useful for versioned reads and replay.

### Aggregate store

A derived store holding the latest known aggregate state. In EDD Core it is not
only for queries; it also acts as the snapshot base for aggregate
reconstruction.

### Append-only log

A sequence that only grows. The event store is an append-only log; events are
never updated or deleted.

### At-least-once delivery

A guarantee that a message or follow-up command may be delivered more than once.
Handlers must be safe under retry.

### Breadcrumbs

A short path describing where a command sits in a workflow. Used both for causal
traceability and for deduplication together with `request-id`.

### Command

An imperative request to change one aggregate. Handled by a pure function that
produces events and optionally identity records.

### Command request log

Store of incoming command requests. Used for debugging and request tracking.

### Command response log

Store of handled responses keyed by `request-id` and `breadcrumbs`. Used to
return stored results for duplicate requests.

### Command store

Store of commands produced by effects and waiting for dispatch.

### Compensation

A new forward action that corrects or reverses the business outcome of an
earlier step. Not a database rollback.

### CQRS

The split between command handling for writes and query handling for reads.

### Dependency

A declared read that the framework resolves before a handler runs and injects
into `ctx`.

### Determinism

The property that the same inputs produce the same output. Required for replay,
retries, and simple tests.

### Effect

A pure function that, given stored events, declares follow-up commands that
should be queued next.

### Event

An immutable fact about the past. Belongs to one aggregate. Stored in the event
log with an `event-seq`.

### Event handler

A pure function that folds one event into aggregate state.

### Event sourcing

The pattern of storing changes as immutable facts and rebuilding current state
from them.

### Event store

The canonical durable store of events. The main source of truth in the system.

### Eventual consistency

The property that some consumer-facing read models may lag behind stored facts
for a short time.

### Fresh aggregate reconstruction

The EDD Core load pattern for command handling: fetch latest snapshot, fetch
events after that version, then fold the tail to compute current aggregate
state.

### Given/When/Then

The common testing shape where history is given, a command is handled, and the
resulting facts are asserted.

### Idempotency

The property that retrying the same action does not create a second real effect.

### Identity store

Store that maps business keys such as emails or external references to aggregate
ids.

### Interaction id

A correlation id for a whole workflow. Shared across commands, events, and
effects that belong to the same larger flow.

### Left fold

The repeated application of an event handler across an ordered event stream to
produce aggregate state.

### Optimistic concurrency control

The write model where concurrent updates race on expected version and one must
retry with fresh state.

### Process aggregate

An aggregate whose job is to remember workflow state and coordinate later steps.

### Projection

A derived read model shaped for a consumer.

### Query

A read-only handler that answers a question from already available state.

### Rejection event

An event that records a failed attempt that still matters to the business.

### Replay

Rebuilding derived state by applying stored events again.

### Request id

An id for one attempted action. Reused across retries so duplicate handling can
be detected safely.

### Singleton aggregate

An aggregate with exactly one instance, usually addressed through a deterministic
`id-fn`.

### Snapshot

Stored aggregate state at a known version, used as the base for efficient
reconstruction.

### Source of truth

The store whose durable contents define what the system knows. In EDD Core, that
is primarily the event store.

### Transactional outbox

The pattern where follow-up work is stored durably in the same write flow as the
state change and dispatched later. In EDD Core this is the role of
`command_store`.
