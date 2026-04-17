---
title: 11. Testing Event-Sourced Systems
description: Why EDD Core tests stay small, explicit, and close to business behavior.
---

## Testing is one of the strongest reasons to use this style

EDD Core is pleasant to test because the important parts of the runtime are data
driven and the business logic is expected to stay pure.

Most tests reduce to a simple pattern.

> **Given** a history of events,
> **when** I handle this command,
> **then** these events are appended and these effects are emitted.

That shape stays useful across command tests, effect tests, dependency tests,
and larger workflow tests.

For many teams this is where the real conversion happens. Not at the level of
theory, but at the moment tests stop feeling like negotiations with hidden
state.

## The mock DAL matches the library model

This repository already leans into that with the mock DAL helpers.

- Seed the event store with prior facts.
- Seed dependencies with known responses.
- Execute a command.
- Inspect the resulting event store, command store, aggregate store, or identity
  store.

That is a very direct way to test business behavior because it mirrors how the
runtime actually works.

## Tests should assert on the right thing

If you are testing command behavior, assert on the events and effects that came
out. If you are testing aggregate reconstruction, assert on the rebuilt
aggregate. If you are testing read behavior, assert on the query result.

That sounds obvious, but many test suites become noisy because they assert on
too many layers at once.

## Rejections fit the same pattern

If a failed attempt becomes a rejection event, then testing failure cases is no
harder than testing success cases. They are both just expected stored results.

That uniformity is one of the underrated advantages of immutable history.

> **Did you know?** Mocks and stubs are not the same thing, and conflating them
> is a common cause of brittle tests. A *stub* returns canned data so the code
> under test can run; a *mock* additionally asserts that specific calls were
> made. Tests that "mock everything" usually mean stubs, and end up coupled to
> implementation details rather than behaviour.

## Effects are easy to inspect

Because effects are stored as commands, you do not need to actually perform the
downstream work in order to test whether it would have happened.

You can inspect `command_store` and assert on the produced commands directly.

## Dependencies are easy to fake honestly

Because dependencies are declared and injected, they are also easy to stub using
plain data.

The test does not need to simulate transport details. It only needs to provide
the dependency response the handler expects.

This makes remote dependencies much less painful to test than imperative service
calls hidden inside the handler body.

## Determinism is still the rule

The more deterministic your handlers and event folds are, the less effort your
tests require.

If a value must vary, inject it through context or command data so the test can
control it explicitly.

## Integration tests should stay focused

Unit-style tests with the mock DAL cover most business behavior well. Real
integration tests still matter for storage constraints, transactional behavior,
serialization, and wiring, but they should stay focused on those concerns.

The point is not to eliminate integration tests. The point is to stop forcing
them to carry all behavior coverage.

This is another version of the same lesson that runs through the book: let each
part do its own job, and the whole system becomes easier to live with.

## Summary

Testing in EDD Core is straightforward because the runtime model is explicit.
History goes in, commands are applied, facts and follow-up commands come out.
That simplicity is not accidental. It is the direct result of immutable state
and pure handlers.
