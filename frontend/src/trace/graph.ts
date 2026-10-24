// Part 14 (specs/graph-view.md): arrays and lists the user confirms as graphs.
// - 14a, matrix: a square two-dimensional array; g[i][j] holding anything but the chosen
//   "no edge" value (or true, for a boolean matrix) is an edge i → j weighted by that value.
// - 14b, lists: row i of a two-dimensional int array, a List<List<Integer>> or a
//   List<Integer>[] lists the neighbours of node i.
// Only the recorded state at the cursor is read; a value that stops fitting is reported, never
// repaired. Layout (presentation only): levels by distance from a start node, following the
// edges at this cursor; edges outside that tree are drawn dashed; nodes the start cannot reach
// start trees of their own below.
import type { State } from './playback.ts';
import type { Pointer } from './pointers.ts';
import type { ObservableEvent, Trace, TraceObject, Value } from './types.ts';

export type GraphForm = 'matrix' | 'lists';

export interface GraphMapping {
  /** Variable referring to the graph (looked up from the outermost frame inward). */
  array: string;
  /** 14b: how the variable holds the graph; a matrix when absent (14a mappings). */
  form?: GraphForm;
  directed: boolean;
  /** The value that means "no edge" in a numeric matrix (the user's choice; -1 by default). */
  noEdge: number;
  /** The node the levels are measured from. */
  start: number;
  /** 14c: one-dimensional arrays the user linked to the nodes (entry i belongs to node i). */
  nodeArrays?: string[];
}

export const NO_EDGE_CHOICES = [-1, 0] as const;

type ArrayObject = TraceObject & { kind: 'array' };

const NUMERIC = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'char']);
const LIST = '(?:java\\.util\\.)?(?:List|ArrayList|LinkedList)';
const INTEGER_LISTS = new RegExp(`^${LIST}<${LIST}<(?:java\\.lang\\.)?Integer>>$`);
const ARRAY_OF_LISTS = new RegExp(`^${LIST}<(?:java\\.lang\\.)?Integer>\\[\\]$`);

/** The graph forms a declared type can be confirmed as. */
export function formsOf(javaType: string): GraphForm[] {
  if (javaType.endsWith('[][]') && !javaType.endsWith('[][][]')) {
    const base = javaType.slice(0, -4);
    if (base === 'int') return ['matrix', 'lists'];
    if (NUMERIC.has(base) || base === 'boolean') return ['matrix'];
  }
  if (INTEGER_LISTS.test(javaType) || ARRAY_OF_LISTS.test(javaType)) return ['lists'];
  return [];
}

/** Variables of the run that can be confirmed as a graph, with the forms each allows. */
export function graphCandidates(trace: Trace): Map<string, GraphForm[]> {
  const out = new Map<string, GraphForm[]>();
  const see = (name: string, javaType: string) => {
    const forms = formsOf(javaType);
    if (forms.length && !out.has(name)) out.set(name, forms);
  };
  for (const b of trace.initialState?.bindings ?? []) see(b.name, b.javaType);
  for (const e of trace.events) {
    if (e.kind === 'DECLARE') see(e.data.binding.name, e.data.binding.javaType);
    if (e.kind === 'CALL') for (const p of e.data.parameters) see(p.name, p.javaType);
  }
  return out;
}

/** 14a: variables that can be confirmed as a matrix (kept for callers of the first increment). */
export function graphVariables(trace: Trace): string[] {
  return [...graphCandidates(trace)].filter(([, forms]) => forms.includes('matrix')).map(([name]) => name);
}

/** Why a mapping does not fit a run (null when it fits). */
export function graphProblem(mapping: GraphMapping, trace: Trace): string | null {
  const forms = graphCandidates(trace).get(mapping.array);
  const form = mapping.form ?? 'matrix';
  if (!forms) return `no variable ${mapping.array} that can hold a graph in this run`;
  if (!forms.includes(form)) return `${mapping.array} cannot be read as ${form === 'matrix' ? 'a matrix' : 'neighbour lists'}`;
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
  /** The cell value, shown as a weight; null for a boolean matrix and for lists. */
  weight: Value | null;
  /** Undirected matrix: the other direction's cell holds a different value (both are shown). */
  reverse?: Value | null;
  /** Part of the level tree (solid), or another edge (dashed). */
  tree: boolean;
  /** The entry is unknown (uncaptured code may have changed it): drawn as unknown, not as a fact. */
  unknown?: boolean;
}

export interface GraphView {
  form: GraphForm;
  /** The object the variable refers to. */
  holder: TraceObject;
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
  /** Matrix: whether it is symmetric. Lists: whether every listed edge is also listed back. */
  symmetric: boolean;
  /** Nodes that start a tree of their own (the start node first). */
  roots: number[];
}

export type Projection = { graph: GraphView } | { problem: string } | null;

const same = (a: Value, b: Value) => JSON.stringify(a) === JSON.stringify(b);

/** The graph at a state: null when the variable is not in scope, or why the value does not fit now. */
export function projectGraph(state: State, mapping: GraphMapping): Projection {
  const ref = lookup(state, mapping.array);
  if (!ref || ref.kind !== 'ref') return ref?.kind === 'null' ? { problem: `${mapping.array} is null` } : null;
  const holder = state.objects.get(ref.objectId);
  if (!holder) return null;
  const outer = holder.kind === 'array' || holder.kind === 'collection' ? holder.elements : null;
  if (!outer) return { problem: `${mapping.array} is not an array or a list` };
  const n = outer.length;
  const rows: (TraceObject & { kind: 'array' | 'collection' })[] = [];
  for (let i = 0; i < n; i++) {
    const e = outer[i];
    if (e.kind !== 'ref') return { problem: e.kind === 'null' ? `row ${i} is null` : `row ${i} is not known here` };
    const row = state.objects.get(e.objectId);
    if (!row || row.kind !== 'array' && row.kind !== 'collection') return { problem: `row ${i} is not an array or a list` };
    rows.push(row);
  }
  const form = mapping.form ?? 'matrix';
  const made = form === 'matrix' ? matrixEdges(holder as ArrayObject, rows as ArrayObject[], mapping) : listEdges(rows, mapping);
  if ('problem' in made) return made;
  const { edges, symmetric, weighted } = made;
  const { place, levels, widest, roots } = layout(n, edges, mapping);
  return { graph: { form, holder, rows: rows.map(r => r.objectId), n, edges, place, levels, widest, weighted, symmetric, roots } };
}

type Made = { edges: Edge[]; symmetric: boolean; weighted: boolean } | { problem: string };

function matrixEdges(matrix: ArrayObject, rows: ArrayObject[], mapping: GraphMapping): Made {
  const n = rows.length;
  for (let i = 0; i < n; i++) {
    if (rows[i].elements.length !== n) return { problem: `not square: row ${i} has ${rows[i].elements.length} entries for ${n} nodes` };
  }
  const boolean = matrix.elementType.startsWith('boolean');
  const present = (v: Value) => v.kind === 'unknown' ? 'unknown' : boolean ? v.kind === 'boolean' && v.value : number(v) !== mapping.noEdge;
  const cell = (i: number, j: number) => rows[i].elements[j];
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
  const weighted = !boolean && edges.some(e => e.weight !== null && number(e.weight) !== 1);
  return { edges, symmetric, weighted };
}

/** 14b: row i lists the neighbours of i. An entry outside 0..n-1 does not fit; repeats are one edge. */
function listEdges(rows: (TraceObject & { kind: 'array' | 'collection' })[], mapping: GraphMapping): Made {
  const n = rows.length;
  const listed = new Set<string>();
  const unknownRows: number[] = [];
  for (let i = 0; i < n; i++) {
    for (const v of rows[i].elements) {
      if (v.kind === 'unknown') { if (!unknownRows.includes(i)) unknownRows.push(i); continue; }
      const j = number(v);
      if (j === null || !Number.isInteger(j)) return { problem: `row ${i} holds ${v.kind}, not a node index` };
      if (j < 0 || j >= n) return { problem: `row ${i} lists ${j}, which is not a node (0..${n - 1})` };
      listed.add(`${i}-${j}`);
    }
  }
  const edges: Edge[] = [];
  let symmetric = true;
  for (const key of listed) {
    const [i, j] = key.split('-').map(Number);
    if (!listed.has(`${j}-${i}`)) symmetric = false;
    if (!mapping.directed && listed.has(`${j}-${i}`) && j < i) continue; // the pair is drawn once
    edges.push({ from: i, to: j, weight: null, tree: false });
  }
  edges.sort((a, b) => a.from - b.from || a.to - b.to);
  // A row with unknown entries: its node shows an unknown loop instead of guessed neighbours.
  for (const i of unknownRows) edges.push({ from: i, to: i, weight: null, tree: false, unknown: true });
  return { edges, symmetric, weighted: false };
}
/** Levels: breadth-first over the known edges, from the start node, then from the smallest node not reached. */
function layout(n: number, edges: Edge[], mapping: GraphMapping) {
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
  return { place, levels: order.length, widest: Math.max(1, ...order.map(l => l.length)), roots };
}

/** 14c: one-dimensional primitive arrays of the run, which may be linked to a graph's nodes. */
export function nodeArrayCandidates(trace: Trace): string[] {
  const out = new Set<string>();
  const see = (name: string, javaType: string) => {
    if (javaType.endsWith('[]') && !javaType.endsWith('[][]') && (NUMERIC.has(javaType.slice(0, -2)) || javaType === 'boolean[]')) out.add(name);
  };
  for (const b of trace.initialState?.bindings ?? []) see(b.name, b.javaType);
  for (const e of trace.events) {
    if (e.kind === 'DECLARE') see(e.data.binding.name, e.data.binding.javaType);
    if (e.kind === 'CALL') for (const p of e.data.parameters) see(p.name, p.javaType);
  }
  return [...out];
}

export interface NodeValues {
  name: string;
  /** The array object, when it is in scope and has one entry per node. */
  objectId?: string;
  values?: Value[];
  boolean?: boolean;
  /** Why no values are shown at this step. */
  note?: string;
}

/** 14c: the linked arrays' entries per node at this state (never a value from another step). */
export function nodeValues(state: State, mapping: GraphMapping, n: number): NodeValues[] {
  return (mapping.nodeArrays ?? []).map(name => {
    const ref = lookup(state, name);
    if (!ref) return { name, note: 'not in scope here' };
    if (ref.kind !== 'ref') return { name, note: ref.kind === 'null' ? 'null' : 'not known here' };
    const array = state.objects.get(ref.objectId);
    if (!array || array.kind !== 'array') return { name, note: 'not an array' };
    if (array.elements.length !== n) return { name, note: `has ${array.elements.length} entries for ${n} nodes` };
    return { name, objectId: array.objectId, values: array.elements, boolean: array.elementType === 'boolean' };
  });
}

/**
 * 14c: index variables at each node. A variable that indexed the graph (`g[u]`, `adj.get(u)`)
 * marks node u; for a matrix, one that indexed a row (`g[u][v]`) marks node v. The 11b rule:
 * recorded accesses written literally with the variable, up to the cursor, at its current value.
 */
export function markers(g: GraphView, pointers: Map<string, Pointer[]>): Map<number, string[]> {
  const out = new Map<number, string[]>();
  const put = (p: Pointer) => {
    if (p.value < 0 || p.value >= g.n) return;
    const names = out.get(p.value) ?? [];
    if (!names.includes(p.name)) names.push(p.name);
    out.set(p.value, names);
  };
  for (const p of pointers.get(g.holder.objectId) ?? []) put(p);
  if (g.form === 'matrix') for (const row of g.rows) for (const p of pointers.get(row) ?? []) put(p);
  return out;
}

/** What the step's own recorded event touched in the graph: a node (g[i], adj.get(i), visited[i]) or an edge. */
export function touched(event: ObservableEvent | null, g: GraphView, state: State, linked: string[] = []): { node?: number; edge?: [number, number]; access?: string } {
  if (!event) return {};
  if ((event.kind === 'ARRAY_READ' || event.kind === 'ARRAY_WRITE') && linked.includes(event.data.objectId)) {
    return { node: event.data.index, access: event.kind === 'ARRAY_READ' ? 'read' : 'write' };
  }
  const row = (id: string | null) => id ? g.rows.indexOf(id) : -1;
  const entry = (i: number, k: number): number | null => {
    const r = state.objects.get(g.rows[i]);
    const v = r && (r.kind === 'array' || r.kind === 'collection') ? r.elements[k] : undefined;
    return v ? number(v) : null;
  };
  if (event.kind === 'ARRAY_READ' || event.kind === 'ARRAY_WRITE') {
    const access = event.kind === 'ARRAY_READ' ? 'read' : 'write';
    if (event.data.objectId === g.holder.objectId) return { node: event.data.index, access };
    const i = row(event.data.objectId);
    if (i < 0) return {};
    if (g.form === 'matrix') return { edge: [i, event.data.index], access };
    const j = access === 'read' ? number(event.data.value) : entry(i, event.data.index);
    return j === null ? {} : { edge: [i, j], access };
  }
  if (event.kind === 'LIBRARY') {
    const args = event.data.arguments;
    const first = args[0] && 'kind' in args[0] ? number(args[0] as Value) : null;
    if (event.data.objectId === g.holder.objectId && event.data.method.endsWith('.get(int)') && first !== null) return { node: first, access: 'read' };
    // A row's for-each next() names the row as objectId; get(k) and add(j) are calls on the row.
    const i = row(event.data.objectId);
    if (i < 0) return {};
    const result = event.data.result && 'kind' in event.data.result ? number(event.data.result) : null;
    if (event.data.method === 'java.util.Iterator.next()' && result !== null) return { edge: [i, result], access: 'read' };
    if (event.data.method.endsWith('.get(int)') && result !== null) return { edge: [i, result], access: 'read' };
    if (event.data.method.endsWith('.add(java.lang.Object)') && first !== null) return { edge: [i, first], access: 'write' };
  }
  return {};
}
