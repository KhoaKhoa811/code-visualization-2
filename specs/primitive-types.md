# Primitive types and operators (Part 8b)

Status: approved by the user on 2026-10-18 ("ok", after the 8b proposal), step 8b
of the [coverage roadmap](coverage-roadmap.md). Extends the
[bytecode tracer](bytecode-tracing.md) and [partial coverage](partial-coverage.md);
trace contract **1.2.0**.

## Newly traced in `main`

- Locals of every primitive type: `byte`, `short`, `char`, `int`, `long`,
  `float`, `double`, `boolean` (plus `String` and `int[]` as before).
- Declarations without initializer: an `unassigned` binding, recorded just before
  the first operation that runs after the declaration in its block. A declaration
  never followed by an operation in its block is not observable and leaves no step.
- Arithmetic `+ - * / %` and unary `-` in int, long, float and double; bitwise
  `& | ^ ~` and shifts `<< >> >>>` on int and long; `& | ^` on booleans in value
  context.
- Explicit primitive casts, one step each (for example `(int) 25.0 = 25`,
  `(byte) 344 = 88`). A cast that compiles to no instruction has no step.
- Comparisons on every numeric type, including `long`, `float` and `double`
  (recorded at `lcmp`/`fcmpl`/`fcmpg`/`dcmpl`/`dcmpg` with the JVM's NaN and
  signed-zero rules), and `char`.
- Compound assignment `op=` for all the operators above, on locals and `int[]`
  elements; `++`/`--` on every numeric local and on `int[]` elements.
- The conditional operator `c ? a : b` in value context.
- `print`/`println` of every primitive type.

Still uncaptured (8a): string concatenation, calls other than printing, other array
types, objects, and the statements listed for 8c and later.

## Steps

| Source | Steps |
| --- | --- |
| `x += e` (local) | EXPRESSION `x + e`, then ASSIGN |
| `a[i] += e` | ARRAY_READ, EXPRESSION, ARRAY_WRITE |
| `a[i]++` | ARRAY_READ, EXPRESSION `+ 1`, ARRAY_WRITE |
| `b++` on a byte/short/char/long/float/double local | one UPDATE (previous, value, result) |
| `(T) e` | EXPRESSION with operator `(T)`, operand and converted result |
| `c ? a : b` | the condition's steps, then only the chosen operand's steps |

Implicit conversions (binary numeric promotion, assignment and compound-assignment
narrowing, a `long` shift distance) are matched in the bytecode but are not steps;
the recorded operands are the promoted values. An int compound assignment with a
small constant is one `iinc` in bytecode; it still produces the same two steps.

## Mapping and recording

The analyzer reproduces javac's code layout exactly. It needs javac's own
constant folding (JLS 15.29, including casts, conditional operators and integer
division by zero, which is not folded) because folded constants emit no
instructions, and javac's `coerce` rules for implicit conversions. Any mismatch
still fails safely: the statement or program falls back as before.

Record tokens: integral values in decimal, `float`/`double` as raw IEEE-754 bits
in hex, so the trace keeps exact values (negative zero, infinities, NaN). The
trusted builder recomputes every arithmetic result, cast and comparison with
Java semantics and stops capture on disagreement.

## Contract 1.2.0

Additive: EXPRESSION operators `& | ^ ~ << >> >>>` and the casts `(byte)`,
`(short)`, `(char)`, `(int)`, `(long)`, `(float)`, `(double)`. 1.1.0 is retired
(exact-version dispatch; no stored traces).

## Results (2026-10-18)

Tracer host tests: 46 passed, including 6 new 8b cases (all primitive types,
bitwise/shift/cast/ternary, NaN and signed-zero comparisons, array compound
assignment, declarations without initializer, overflow and long division by
zero). Each traced program matched the original's output, exit status and
uncaught exception; each trace passed the shared checker. Earlier 8a cases that
used compound assignment or uninitialized declarations as "unsupported" examples
now use library calls instead.
