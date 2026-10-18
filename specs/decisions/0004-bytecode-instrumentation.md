# ADR 0004: Bytecode instrumentation for runtime facts

Status: **Accepted** (2026-10-18). Recommended by the Part 7a spike; the user approved
Part 7b, which builds on it ("please continue implement part 7b"). See [bytecode-tracing.md](../bytecode-tracing.md).

## Context

The requirements proposed source instrumentation (JavaParser) and said a prototype
had been verified, but no such artifact exists in this repository. The product goal
is to visualize any user-written Java program as correctly as possible, preserving
evaluation order, side effects, short-circuiting, exceptions, object identity and
distinct recursive frames, without evaluating anything twice.

The Part 7a spike ([report](../../runner/tracer-spike/SPIKE_REPORT.md)) instrumented
compiled classes with ASM and compared behavior with the original programs.

## Decision

1. Capture runtime facts by instrumenting the compiled bytecode of the submitted
   program with ASM, inside the isolated runner. Each covered instruction is
   replaced by a recorder helper that performs the same instruction once and
   records operands/results from the operand stack.
2. Derive source ranges, expression meaning (for example the source comparison
   operator) and step grouping by matching recorded operations to the javac
   compiler tree (`com.sun.source`) of the same source version.
3. Operations not yet covered still execute normally; their facts are reported as
   missing capture, and playback follows the existing safe-prefix rules.
4. Reject user code that references the recorder's package before instrumentation.

## Consequences

- Behavior preservation no longer depends on proving each source rewrite.
  Programs using unsupported constructs run identically; only their facts are absent.
- Expression-level highlighting depends on the mapping in point 2, which is not yet
  verified. Line-level highlighting is available immediately; Step still advances
  one recorded operation at a time, so several clicks can stay on one line.
- javac code-generation patterns (negated branches, array initializers, synthetic
  methods) must be normalized before playback.
- New dependency: `org.ow2.asm:asm` and `asm-tree` (9.9.1 in the spike).
- Supersedes the requirements' proposed source-instrumentation baseline once accepted.
