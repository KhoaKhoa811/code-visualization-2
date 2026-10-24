# Dynamic programming tables (Part 20)

Status: approved by the user on 2026-10-24 ("I want its visualization to have a full matrix of how
it store the value and reuse it", then "yes, please implement it"). Frontend only; the trace
contract (1.12.0) is unchanged. Builds on [motion](motion.md) Part 18a ("computed from").

## When an array or map is a DP table

Decided at each cursor from the recorded steps 1..k only (no future facts; stepping back gives
the same answer). Candidates are the arrays (one or two dimensions) and maps with integer keys
that variables refer to.

- **Bottom-up:** at least 2 writes compute a cell from other cells of the same table, reading 2 or
  more of them, or storing a value unlike the one cell read (`dp[i] = dp[i - 1] + a[i]`). A sort
  or a shift copies a cell's value unchanged, so it is not a computation (bubble and insertion
  sort are not DP tables).
- **Top-down (memoization):** at least 2 writes made in a call of a method that has already called
  itself during that call (`long r = fib(n - 1) + fib(n - 2); memo[n] = r;`), and some computed
  cell read again later. `memo.put(n, v)` and `memo.get(n)` on a map with integer keys count like
  `memo[n] = v` and `memo[n]`.

## What each cell shows

- **computed** (filled, green), with **#n**, its order of first computation;
- **not yet**: never written by a computation. It shows its current value dimmed: Java's default
  (a base row such as `best[0][c] = 0`) or an initial fill (`Arrays.fill(memo, -1)`), which is not a
  computation either;
- **↻n**: how many times its stored value was read again after it was computed. Repeated reads
  of a cell by one call with no table write between them are one reuse
  (`if (memo[n] != -1) return memo[n];`).

## What a step shows

- A **write** that computes a cell: the statement, the stored value, and what it came from: the
  cells it read (cells of other arrays are named by their variable, `weight[1] = 3`) and the calls
  it made with their results (`fib(5) = 5, fib(4) = 3`). When the statement makes no call itself,
  the recursive calls its call made since its previous write are shown. Arrows run from the cells
  of the table it read to the written cell. The written cell is never its own source.
- A **read** of a computed cell: "reuses memo[4] = 3, stored at step 61".
- A **read** of a cell not computed yet: "memo[5] is not computed yet (it holds -1)".
- **Clicking a cell**: when and by which statement it was computed, from what, how often it was
  reused, and which later cells used it, with arrows to both.

## Code and tests

- `frontend/src/trace/dp.ts` (`dpLog`, `dpTables`), `dataflow.ts` (`originOf`: reads and calls of
  a write's statement), `views/DpPanel.tsx` (panel `dp`, full width).
- Unit: `dp.test.ts` on traces of grid paths, a memo array and a memo map, and 0/1 knapsack, with
  bubble sort, a grid copy and a heap class as non-tables; no future facts before a write.
- Browser: "a DP table shows every cell ... (20)" on the examples "DP: 0/1 knapsack" and "DP:
  Fibonacci with a memo".

## Limits

- `static` fields are not traced yet, so a memo or table kept in a static field of Main runs
  uncaptured; pass it as a parameter or keep it in a local variable.
- Maps with non-integer keys (`"i,j"` strings) are not shown as tables.
