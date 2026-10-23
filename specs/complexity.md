# Complexity estimates (Part 15)

Status: approved by the user on 2026-10-24 ("I don't need it to be exactly correct. I just want a
result so that I can compare those algos", then "ok" to 15a and 15b). Trace contract **1.12.0**
(optional `complexity`), plan format 12.

## What it is, and what it is not

A best-effort **time** complexity per method, for comparing algorithms. It comes from the shape
of the code only, never from the algorithm's name, and it is not a runtime fact: it is kept out
of the events, in the trace's own `complexity` section. It is not a proof. Where a part had to
be guessed the estimate is marked approximate and shown with `≈`.

Everything is in one size `n`: every size-like expression (`n`, `a.length`, `list.size()`, a
parameter) counts as n, and literal constants as O(1). A grid n × m therefore reads as n².

## Rules (15a, `runner/tracer/.../Complexity.java`)

- **for loops**: the count from the header. `i = a; i < b; i += c` runs n times when `a` or `b`
  is a size (a fixed number of times when both are constants); `i *= c`, `i /= c`, `i <<= 1`,
  `i >>= 1` run log n times. A bound using an outer loop variable (`j < i`) counts that
  variable's range (n). An unreadable header is guessed n.
- **for-each**: n.
- **while / do** (guessed): a condition variable halved or set from a halving expression
  (`mid = (lo + hi) / 2`, `n /= 10`) gives log n; moved step by step (`i++`, `x = x - 1`) n; a
  loop until a collection is empty or an iterator ends n; anything else n.
- **Composition**: a loop multiplies its count by its body; statements in sequence take the
  larger; `if`, `switch` and `?:` take the more expensive branch; `new T[a][b]` costs a × b.
- **Calls**: a method of the program costs its own estimate (mutual recursion is guessed n);
  library calls have listed costs (for example `Arrays.sort` n log n, `ArrayList.contains` n,
  `HashMap.get` 1, `TreeMap.put` and `PriorityQueue.poll` log n, `String.substring` n); others 1.
  Lambdas are not followed.
- **Direct recursion**: from the number of self-calls one invocation makes (calls in different
  branches are alternatives, one of them runs) and how an argument shrinks, with the work besides
  the calls `f(n) = n^d log^k n`:
  - a parameter minus a constant (`n - 1`): one call n · f(n); `a ≥ 2` calls aⁿ.
  - halved (`n / 2`, `mid`, `(lo + hi) / 2`): the master theorem for `a T(n/2) + f(n)`.
  - a range split at a computed point (quick sort's `p - 1`): taken as halves, the average case
    (approximate).
  - a self-call inside a loop (permutations, backtracking): exponential (approximate).
  - unreadable arguments: taken as one step smaller (approximate).

## Showing it (15b)

- **Editor**: after each method's declaration line, `time O(n²)` (or `time ≈ O(log n)`); hovering
  lists the reasons. Shown only while the source is the one that ran.
- **Complexity panel**: every method, slowest-growing first
  (O(1) < O(log n) < O(n) < O(n log n) < O(n²) < O(2ⁿ)); the estimate opens to its reasons; and
  what this run measured: calls, and steps run in the method including the methods it called
  (a recursive method's steps count once). Measured counts are facts of the whole recorded run.

## Contract 1.12.0

Top-level optional `complexity`: per method `methodId` (as in trace frames), `name`, `bound`,
`growth` (`exponentialBase`, `polynomial`, `logarithmic`, the ordering key), `approximate`,
`reasons`, and `source` (the method's name). Absent when there are no estimates (compile errors).
An analysis failure gives no estimates and never stops a run.

## Tests

`ComplexityTest` (loops, nesting, calls, library calls, while loops, recursion: factorial,
Fibonacci, Hanoi, binary search, merge sort, quick sort, fast power, permutations; the plan round
trip). Frontend `complexity.test.ts` (ranking, measured counts) and the browser test on the
example "Compare algorithms".
