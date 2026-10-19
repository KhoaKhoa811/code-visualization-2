# Objects and fields (Part 8e)

Status: approved by the user on 2026-10-19 ("ok", after the 8e proposal), step 8e
of the [coverage roadmap](coverage-roadmap.md). The object-graph view moves into
Core V1. Trace contract **1.4.0**. Builds on [methods](methods.md) (Part 8d) and
the object budgets of [execution-isolation.md Part 4t](execution-isolation.md).

## Traced classes

A *traced class* is a plain class declared in the submitted file, either at top
level or as a `static` member class of `Main`. It must not be an interface, enum,
record, abstract, generic, inner (non-static) or local class, must not extend or
implement anything, and no class in the file may extend it (so every instance
call on it has exactly one target). Every instance field must have a traced type:
a primitive, `String`, a traced class, or an array of those. Static fields may have
any type; they are not traced and code using them runs uncaptured.

Traced types for locals, parameters, results, fields and array elements now
include traced classes (`Node`, `Main.Node`) and arrays of them.

## Newly traced

- `new C(args)`: one ALLOCATE step with every field at its default value, then the
  constructor as a CALL (with a `this` binding) and RETURN, like a 8d method.
  Field initializers run inside the constructor as field writes. A constructor
  is traced when it does not start with `this(...)` and every initializer of an
  instance field is a constant, `null`, or an array creation with constant lengths
  or constant elements; otherwise the constructor runs silently and the object is
  captured when covered code first sees it.
- Instance methods and static methods of traced classes (8d rules; instance
  methods bind `this` first).
- Field reads `p.f`, `f`, `this.f` and writes `p.f = e` (FIELD_READ, FIELD_WRITE),
  compound assignment and `++`/`--` on numeric fields (read, operation, write),
  boolean fields as conditions, `==`/`!=`/`null` tests on object references.

Still uncaptured: static fields, inheritance and interfaces, generic classes,
inner and local classes, lambdas, `toString`/`equals`/`hashCode` calls, objects
of library classes (8f), and exceptions (8h).

## Contract 1.4.0

- Object value kind `object`: `objectId`, `className`, `fields` (ordered as
  declared: `name`, `javaType`, `value`). `javaType` admits traced class names.
- FIELD_READ `{objectId, field, value}` and FIELD_WRITE `{objectId, field,
  previous, value}` steps.
- UNCAPTURED gains `objects` (re-read field values that changed) and
  `unknownObjects` (objects it may have changed that were not re-read).
- References inside objects captured or allocated within one step may point to
  each other (cycles); every reference resolves by the end of that step's events.

## Capture

Objects created by traced code are known from their ALLOCATE and every later
field write. An object first seen by traced code (created by uncaptured code or a
silent constructor) is captured with everything newly reachable from it,
breadth-first, cycles included, as bookkeeping in the step that first uses it.
After an uncaptured statement that may write arrays or fields, every known array
and object is re-read.

At most **1,024 captured identities per run** (arrays, strings and objects).
One more halts the run: execution `limited`, capture `partial`, diagnostic
`TRAVERSAL_LIMIT`. The 8,192-slot and 16-hop limits of Part 4t are not enforced yet.

A `null` receiver of a traced field access throws `NullPointerException` from a
recorder check, which records the failure step. The message names the field
(`Cannot read field "next" because the object is null`), not the JVM's helpful
message, and the stack trace includes a recorder frame (the same known limit as
arrays since 7b). A `null` receiver of an instance call throws the JVM's own
exception without a failure record, so capture ends as partial there.

## Raw records

`O<id> <class> <n> (<field> <value>){n}` an object's fields (first sight or
re-read), `T` the identity limit; field steps use `<site> r<id> <value>`.

## Results

Pending.
