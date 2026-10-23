# Graph view (Part 14)

Status: approved by the user on 2026-10-23 ("1. it's good 2. use -1 3. layout what spreads
nodes", then "A tree grown from a start node, with nodes in layers by distance"). Frontend only:
the trace contract (1.11.0) is unchanged. Like the heap view ([heap view](heap-view.md)), a graph
is the user's interpretation of recorded arrays and lists: it is confirmed, never inferred from
the algorithm, and checked against each run's recorded variables.

## Confirming a graph

"Show a variable as a graph…" offers the run's variables that can hold one:

| Form | Types | Edge |
| --- | --- | --- |
| Adjacency matrix (14a) | square `int[][]` (and other numeric) or `boolean[][]` | `g[i][j]` other than the "no edge" value (`-1` by default, or `0`), or `true`: an edge i → j weighted by the value |
| Neighbour lists (14b) | `int[][]` rows, `List<List<Integer>>`, `List<Integer>[]` | each entry j of row i: an edge i → j; repeats are one edge |

The user also chooses directed or undirected and the start node. The editor only suggests, from
the state at the cursor: the form that fits, and undirected while the matrix is symmetric (or
every listed edge is listed back). Undirected matrices whose two cells differ show both values
and a note. Weights are drawn when some weight is not 1.

## Layout

Presentation only, from the edges at the cursor: levels by breadth-first distance from the start
node (neighbours in index order). Edges of that level tree are solid, other edges dashed. Nodes
the start cannot reach start trees of their own below, from the smallest such node. Nodes and
edges move between steps while Motion is on.

## What a step shows

- Its own recorded event lights what it touched: `g[i]` or `adj.get(i)` node i; `g[i][j]` edge
  i → j; for lists a row's read, `get(k)`, for-each `next()` (the element read) or `add(j)`.
- 14c: a variable that indexed the graph (`g[u]`, `adj.get(u)`) marks node u, and for a matrix
  one that indexed a row (`g[u][v]`) marks node v, by the [index arrow](motion.md) rule (accesses
  written literally with the variable, up to the cursor, at its current value). A position in a
  list is not a node, so it marks nothing.
- 14c: linked one-dimensional arrays (entry i belongs to node i): one boolean array fills the
  nodes where it is true (`visited`); other arrays are shown under each node (`dist 3`). A
  linked array of another length, out of scope or null is noted, not shown. Reading or writing
  an entry lights its node.

## When it does not fit

Reported at that step, never repaired: not square, a null or unknown row, an entry that is not
a node index. Unknown cells (uncaptured code may have changed them) are drawn as unknown and
not followed by the layout. Out of scope: "not in scope at this step".

## Code

- `frontend/src/trace/graph.ts` (pure, `graph.test.ts`): `projectGraph`, `touched`, `markers`,
  `nodeValues`; `trace/pointers.ts` also counts `….get(name)`.
- `frontend/src/views/GraphMappings.tsx`, `frontend/src/views/GraphPanel.tsx`.
- Examples "Graph BFS (matrix)" and "Graph DFS (lists)"; three browser tests.
