# Reading the example traces

These JSON files are authored contract examples, not captured runtime output.
Read source.text first, initialState second, and then events in seq order.
stepEnds says how far each visible cursor may read. The manifest names the
expected schema result and explains why each fixture exists.

## Start with three small examples

1. `valid/scalar.json`: declaration, assignment, and terminal bookkeeping.
2. `valid/array-alias.json`: both a and b refer to object A. Cursor 6 reads A[1];
   cursor 7 computes the addition; cursor 8 commits the write. No duplicate
   array is created for b. Backward to cursor 7 must restore A = [3, 1].
3. `valid/partial-prefix.json`: the same source, but only seven operations are
   accepted. A remains [3, 1]. The unrecorded write and terminal cleanup are
   deliberately absent, even though execution has ended.

Other valid examples cover failed writes with earlier side effects, output-only
execution, zero-operation termination, recursive calls/returns, String identity
registration, short-circuiting, array length, scope lifetimes, and postfix updates.

## Cursor expectations for future replay tests

| Fixture/cursor | Expected recorded state or observation |
| --- | --- |
| scalar / 1 | x = 5 in main |
| scalar / 2 | Assignment observation is 8; terminal bookkeeping then removes main and its bindings |
| array-alias / 3 | a and b refer to the same A = [3, 1] |
| array-alias / 7 | A unchanged; addition observation is 2 |
| array-alias / 8 | A = [2, 1]; main bindings have ended; write observation remains inspectable |
| partial-prefix / 7 | A = [3, 1]; active-frame prefix retained; capture is partial |
| failed-write / 4 | x = 1 and A = [0], before the exception operation |
| failed-write / 5 | Exception observation, unchanged A, recorded unwind ends main |
| recursion / 7 | Three distinct helper frames with n = 2, 1, 0 |
| recursion / 9 | sum(0) returned 0; two helper frames remain |
| recursion / 13 | sum(2) returned 3; main remains before receiving declaration |
| string-capture / 1 | Observed String S contains UTF-16 [104, 105]; no invented allocation click |

Final cursor cleanup removes ended bindings, not the last operation's captured
result or source highlight. The zero-operation fixture applies only recorded
terminal bookkeeping at cursor zero. These state expectations were manually
reviewed, not verified by a runtime replay implementation.

## Invalid does not always mean schema-invalid

`invalid/` documents must fail structural validation at the manifest's indicated
keyword/path. `semantic-invalid/` documents intentionally pass the schema: their
wrong hash, sequence gap, dangling identity, out-of-range Java int, or future
cursor boundary requires more than shape checking.

The test script detects the metadata subset (hash, sequence, boundaries). It
does not implement reference/type resolution or Java arithmetic. Passing the
semantic-invalid fixtures through Ajv is expected and demonstrates why production
playback will need all specification section 12 gates.

`values.json` checks wire representations independently of current language
coverage. A representable double or long is not a claim that its tracing exists.
