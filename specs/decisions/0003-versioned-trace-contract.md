# ADR 0003: Versioned trace facts with explicit playback boundaries

Date: 2026-10-07. Status: proposed contract baseline for Part 3 review.

## Context

The runner, backend, and frontend need the same description of recorded Java
facts. Java numeric values cannot all be represented faithfully by JSON numbers.
Bookkeeping must not add clicks, and partial capture must not expose unsafe state.

## Decision

Define exact-version JSON Schema 2020-12 documents with closed event variants.
Separate execution outcome from capture coverage. Store original source identity,
typed values, explicit object/binding/frame/scope IDs, and ordered operations.
Use decimal strings for integral Java values and raw bit strings for floating
point. Use UTF-16 code units for captured strings to retain exact contents.

Keep observable steps separate from bookkeeping sequence numbers. Explicit
stepEnds identify accepted replay prefixes; semantic validation checks them.
No rendering/layout/mapping information belongs in this contract.

## Reasons and consequences

Explicit boundaries prevent extra bookkeeping clicks and future-event leakage.
Raw values avoid loss through frontend numeric conversion. Closed variants reject
unknown facts instead of silently dropping mutations. Exact-version dispatch
requires deliberate schema/consumer changes when coverage expands.

JSON Schema validates document structure, not Java correctness, identity
resolution, source hashes, or replay consistency. These require additional gates.
This decision adds no runtime producer, isolation mechanism, or rendering engine.
