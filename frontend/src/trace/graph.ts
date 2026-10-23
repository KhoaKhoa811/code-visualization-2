// Part 14a (specs/graph-view.md): a square two-dimensional array the user confirms as an
// adjacency matrix. g[i][j] holding anything but the chosen "no edge" value (or true, for a
// boolean matrix) is an edge from node i to node j with that value as its weight. Only the
// recorded state at the cursor is read; an array that stops fitting is reported, never repaired.
// Layout (presentation only): levels by distance from a start node, following the edges at
// this cursor; edges outside that tree are drawn dashed; nodes the start cannot reach start
// trees of their own below.
import type { State } from './playback.ts';
import type { Trace, TraceObject, Value } from './types.ts';

export interface GraphMapping {
  /** Variable referring to the matrix (looked up from the outermost frame inward). */
  array: string;
  directed: boolean;
  /** The value that means "no edge" in a numeric matrix (the user's choice; -1 by default). */
  noEdge: number;
  /** The node the levels are measured from. */
  start: number;
}

export const NO_EDGE_CHOICES = [-1, 0] as const;

type ArrayObject = TraceObject & { kind: 'array' };

const NUMERIC = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'char']);

/** Variables of the run that are two-dimensional numeric or boolean arrays (matrix candidates). */
export function graphVariables(trace: Trace): string[] {
  const out = new Set<string>();
  const see = (name: string, javaType: string) => {
    if (!javaType.endsWith('[][]') || javaType.endsWith('[][][]')) return;
    const base = javaType.slice(0, -4);
    if (NUMERIC.has(base) || base === 'boolean') out.add(name);
  };
  for (const b of trace.initialState?.bindings ?? []) see(b.name, b.javaType);
  for (const e of trace.events) {
    if (e.kind === 'DECLARE') see(e.data.binding.name, e.data.binding.javaType);
    if (e.kind === 'CALL') for (const p of e.data.parameters) see(p.name, p.javaType);
  }
  return [...out];
}

/** Why a mapping does not fit a run (null when it fits). */
export function graphProblem(mapping: GraphMapping, trace: Trace): string | null {
  if (!graphVariables(trace).includes(mapping.array)) return `no two-dimensional numeric or boolean array ${mapping.array} in this run`;
  if (!Number.isInteger(mapping.start) || mapping.start < 0) return 'the start node must be a node index (0 or more)';
  return null;
}

function lookup(state: State, name: string): Value | undefined {
  for (const frame of state.frames) {
    const b = state.bindings.find(x => x.frameId === frame.frameId && x.name === name);
    if (b) return b.value;
  }
  return undefined;
}

function number(v: Value): number | null {
  if (v.kind === 'byte' || v.kind === 'short' || v.kind === 'int' || v.kind === 'long') return Number(v.value);
  if (v.kind === 'char') return v.value;
  if (v.kind === 'float' || v.kind === 'double') {
    const view = new DataView(new ArrayBuffer(8));
    if (v.bits.length === 8) { view.setUint32(0, parseInt(v.bits, 16)); return view.getFloat32(0); }
    view.setBigUint64(0, BigInt(`0x${v.bits}`));
    return view.getFloat64(0);
  }
  return null;
}

export interface Edge {
  from: number;
  to: number;
  /** The cell value, shown as a weight; null for a boolean matrix. */
  weight: Value | null;
  /** Undirected: the other direction's cell holds a different value (both are shown). */
  reverse?: Value | null;
  /** Part of the level tree (solid), or another edge (dashed). */
  tree: boolean;
  /** The cell is unknown (uncaptured code may have changed it): drawn as unknown, not as a fact. */
  unknown?: boolean;
}

export interface GraphView {
  matrix: ArrayObject;
  /** Row object id per node, for matching recorded accesses. */
  rows: string[];
  n: number;
  edges: Edge[];
  /** Level (distance from the start of its tree) and order within the level, per node. */
  place: { level: number; slot: number }[];
  levels: number;
  widest: number;
  /** Whether every edge's weight should be drawn (some weight other than 1). */
  weighted: boolean;
  symmetric: boolean;
  /** Nodes that start a tree of their own (the start node first). */
  roots: number[];
}

export type Projection = { graph: GraphView } | { problem: string } | null;

/** The graph at a state: null when the variable is not in scope, or why the array does not fit now. */
export function projectGraph(state: State, mapping: GraphMapping): Projection {
  const ref = lookup(state, mapping.array);
  if (!ref || ref.kind !== 'ref') return ref?.kind === 'null' ? { problem: `${mapping.array} is null` } : null;
  const matrix = state.objects.get(ref.objectId);
  if (!matrix || matrix.kind !== 'array') return null;
  const n = matrix.elements.length;
  const rows: ArrayObject[] = [];
  for (let i = 0; i < n; i++) {
    const e = matrix.elements[i];
    if (e.kind !== 'ref') return { problem: e.kind === 'null' ? `row ${i} is null` : `row ${i} is not known here` };
    const row = state.objects.get(e.objectId);
    if (!row || row.kind !== 'array') return { problem: `row ${i} is not an array` };
    if (row.elements.length !== n) return { problem: `not square: row ${i} has ${row.elements.length} entries for ${n} nodes` };
    rows.push(row);
  }
  const boolean = matrix.elementType.startsWith('boolean');
  const present = (v: Value) => v.kind === 'unknown' ? 'unknown' : boolean ? v.kind === 'boolean' && v.value : number(v) !== mapping.noEdge;
  const cell = (i: number, j: number) => rows[i].elements[j];
  const same = (a: Value, b: Value) => JSON.stringify(a) === JSON.stringify(b);
  let symmetric = true;
  for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) if (!same(cell(i, j), cell(j, i))) symmetric = false;

  const edges: Edge[] = [];
  for (let i = 0; i < n; i++) {
    for (let j = mapping.directed ? 0 : i; j < n; j++) {
      const here = present(cell(i, j)), back = i === j || mapping.directed ? false : present(cell(j, i));
      if (!here && !back) continue;
      // Undirected: one edge for the pair, from the cell that holds it.
      const [from, to] = here ? [i, j] : [j, i];
      const edge: Edge = { from, to, weight: boolean ? null : cell(from, to), tree: false };
      if (here === 'unknown' || back === 'unknown') edge.unknown = true;
      if (!mapping.directed && i !== j && here && back && !same(cell(i, j), cell(j, i))) edge.reverse = boolean ? null : cell(j, i);
      edges.push(edge);
    }
  }

  // Levels: breadth-first over the edges at this cursor, from the start node, then from the
  // smallest node not reached yet. Unknown cells are not followed.
  const out = new Map<number, number[]>();
  for (const e of edges) {
    if (e.unknown) continue;
    out.set(e.from, [...(out.get(e.from) ?? []), e.to]);
    if (!mapping.directed) out.set(e.to, [...(out.get(e.to) ?? []), e.from]);
  }
  for (const list of out.values()) list.sort((a, b) => a - b);
  const level = new Array<number>(n).fill(-1);
  const parent = new Array<number>(n).fill(-1);
  const roots: number[] = [];
  let base = 0;
  const order: number[][] = [];
  const grow = (root: number) => {
    roots.push(root);
    level[root] = base;
    const queue = [root];
    let deepest = base;
    while (queue.length) {
      const u = queue.shift()!;
      (order[level[u]] ??= []).push(u);
      deepest = Math.max(deepest, level[u]);
      for (const v of out.get(u) ?? []) {
        if (level[v] !== -1) continue;
        level[v] = level[u] + 1;
        parent[v] = u;
        queue.push(v);
      }
    }
    base = deepest + 1;
  };
  if (mapping.start < n) grow(mapping.start);
  for (let i = 0; i < n; i++) if (level[i] === -1) grow(i);
  for (const e of edges) e.tree = !e.unknown && e.from !== e.to && (parent[e.to] === e.from || !mapping.directed && parent[e.from] === e.to);
  const place = new Array<{ level: number; slot: number }>(n);
  order.forEach((nodes, l) => nodes.forEach((u, slot) => { place[u] = { level: l, slot }; }));
  const weighted = !boolean && edges.some(e => e.weight !== null && number(e.weight) !== 1);
  return {
    graph: {
      matrix, rows: rows.map(r => r.objectId), n, edges, place, levels: order.length,
      widest: Math.max(1, ...order.map(l => l.length)), weighted, symmetric, roots,
    },
  };
}
