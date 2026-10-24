// Part 19c-19f (specs/structure-detection.md): structure detection, rule set rank-2. It builds on
// rank-1 (Part 10c, rankings.ts) and keeps its rules: a pure function of the trace prefix up to
// cursor k, the candidate and the rule set; no scores; counts that say what they count. rank-2
// reads each link field's role from the recorded objects (a next/prev pair that mirrors itself, a
// parent pointer, children held in an array, list or map), adds doubly linked lists, trees with
// children, tries, chained hash tables, heaps inside a class and graphs of index lists, and marks
// a candidate to be shown automatically when it is Strong and no other candidate of its subject is.
import type { Playback, State } from './playback.ts';
import { assessState, type Assessment, type Level } from './rankings.ts';
import { suggestTrees, type FieldInfo, type TreeMapping } from './tree.ts';
import { projectList, type StructMapping } from './structures.ts';
import { childrenField, projectChildren, type ChildrenMapping } from './children.ts';
import { projectChains, type ChainMapping } from './chains.ts';
import type { HeapMapping } from './heap.ts';
import { projectGraph, type GraphMapping } from './graph.ts';
import { isObservable, type ObservableEvent, type TraceObject, type Value } from './types.ts';
import { reachable } from './reach.ts';

export const RULES = 'rank-2';

/** What a detected structure is drawn with. */
export type Shape =
  | { kind: 'tree'; mapping: TreeMapping }
  | { kind: 'list'; mapping: StructMapping & { kind: 'list' } }
  | { kind: 'array stack' | 'array queue'; mapping: StructMapping }
  | { kind: 'children'; mapping: ChildrenMapping }
  | { kind: 'chains'; mapping: ChainMapping }
  | { kind: 'heap'; mapping: HeapMapping }
  | { kind: 'graph'; mapping: GraphMapping }
  | { kind: 'deque'; variable: string; mode: 'stack' | 'queue' };

export interface Detected {
  /** Stable across steps: the subject and the view. */
  key: string;
  subject: string;
  /** The view's name as shown (doubly linked list, trie, hash table with chaining, ...). */
  view: string;
  level: Level;
  evidence: string[];
  missing: string[];
  contradictions: string[];
  observation?: string;
  shape: Shape | null;
  /** Strong, and no other candidate of the same subject is Strong. */
  auto: boolean;
}

type ObjectNode = TraceObject & { kind: 'object' };
const RANK: Record<Level, number> = { Strong: 3, Possible: 2, Weak: 1, Unknown: 0, Incompatible: -1 };
const INTS = new Set(['int', 'short', 'byte', 'long']);

/** Detection at cursor k from the reconstructed state and the events up to step k. */
export function detect(playback: Playback, k: number): Detected[] {
  const view = playback.at(k);
  const state = view.beforeEnd ?? view.state;
  const ops = playback.trace.events.slice(0, playback.boundary(view.cursor)).filter(isObservable);
  return detectState(state, ops, view.cursor);
}

export function detectState(state: State, ops: ObservableEvent[], k: number): Detected[] {
  const steps = `steps 1–${k}`;
  const classes = new Map<string, FieldInfo[]>();
  for (const o of state.objects.values()) if (o.kind === 'object' && !classes.has(o.className)) classes.set(o.className, o.fields.map(f => ({ name: f.name, javaType: f.javaType })));
  const out: Detected[] = [];
  const live = reachable(state);

  // rank-1 candidates, kept where rank-2 has nothing better to say about the class or variable.
  const replaced = new Set<string>(); // subject|view pairs rank-2 replaces
  for (const [className, fields] of classes) {
    // Link roles are read from objects the program can still reach: an unlinked node keeps stale links.
    const nodes = objectsOf(state, className).filter(n => live.has(n.objectId));
    const links = fields.filter(f => f.javaType === className).map(f => f.name);
    const pair = inversePair(state, nodes, links);
    const parent = parentLink(state, nodes, links);
    if (pair) { // a next/prev pair that mirrors itself is a doubly linked list, not a binary tree
      replaced.add(`${className}|tree`).add(`${className}|list`);
      out.push(doublyList(state, className, fields, pair, steps));
    } else if (parent && links.length === 3) {
      replaced.add(`${className}|tree`);
      const [left, right] = links.filter(l => l !== parent);
      const value = fields.find(f => !f.javaType.includes(className))?.name;
      if (value) {
        const tree = assessState(state, ops, k, { trees: [{ className, value, left, right }] }).find(a => a.subject === className && a.view === 'tree');
        if (tree) out.push(from(tree, { kind: 'tree', mapping: { className, value, left, right } }, `binary tree (${parent} points to the parent)`));
      }
    }
    for (const f of fields) {
      const kind = childrenField(className, f.javaType);
      if (!kind) continue;
      const value = fields.find(x => x.name !== f.name && !x.javaType.includes(className))?.name ?? null;
      out.push(childrenTree(state, { className, children: f.name, value, keyed: kind.keyed }, fields, steps));
      break;
    }
    for (const f of fields) {
      if (!f.javaType.endsWith('[]')) continue;
      const nodeClass = f.javaType.slice(0, -2);
      const nodeFields = classes.get(nodeClass);
      const next = nodeFields?.filter(x => x.javaType === nodeClass);
      if (!nodeFields || next?.length !== 1 || nodeClass === className) continue;
      const shown = nodeFields.filter(x => x.javaType !== nodeClass).slice(0, 2).map(x => x.name);
      out.push(chains(state, { owner: className, array: f.name, nodeClass, next: next[0].name, shown }, steps));
    }
    const arrays = fields.filter(f => f.javaType.endsWith('[]') && ['int', 'long', 'double', 'float', 'short', 'byte', 'char'].includes(f.javaType.slice(0, -2)));
    const ints = fields.filter(f => INTS.has(f.javaType));
    if (arrays.length === 1 && ints.length >= 1) {
      const heap = heapInClass(state, ops, className, arrays[0].name, ints[0].name, steps);
      out.push(heap);
      // Parent-child swaps are not what a stack or a queue on an array does.
      if (heap.level === 'Strong') replaced.add(`${className}|array stack`).add(`${className}|array queue`);
    }
  }

  for (const a of assessState(state, ops, k)) {
    if (replaced.has(`${a.subject}|${a.view}`)) continue;
    if (a.view === 'max-heap' || a.view === 'min-heap') continue; // replaced below by the rank-2 heap rule
    out.push(from(a, shapeOf(a, classes), a.view === 'tree' ? 'binary tree' : a.view === 'list' ? 'linked list' : a.view));
  }

  // Variables, outermost frame first: numeric arrays (heap, rank-2 rule) and lists of index lists (graph).
  const seen = new Set<string>();
  for (const frame of state.frames) {
    for (const b of state.bindings.filter(x => x.frameId === frame.frameId)) {
      if (b.value.kind !== 'ref' || seen.has(b.name)) continue;
      const target = state.objects.get(b.value.objectId);
      if (target?.kind === 'array' && ['int', 'long', 'double', 'float', 'short', 'byte', 'char'].includes(target.elementType)) {
        seen.add(b.name);
        out.push(heapVariable(target, b.name, ops, steps));
      } else if (target && /^java\.util\.(List|ArrayList|LinkedList)<java\.util\.(List|ArrayList|LinkedList)<java\.lang\.Integer>>$/.test(b.javaType)) {
        seen.add(b.name);
        out.push(listGraph(state, b.name, steps));
      }
    }
  }

  // Shown automatically: Strong, and the only Strong candidate of its subject.
  const strong = new Map<string, number>();
  for (const d of out) if (d.level === 'Strong' && d.shape) strong.set(d.subject, (strong.get(d.subject) ?? 0) + 1);
  for (const d of out) d.auto = d.level === 'Strong' && d.shape !== null && strong.get(d.subject) === 1;
  return out.sort((a, b) => a.subject.localeCompare(b.subject) || RANK[b.level] - RANK[a.level] || a.view.localeCompare(b.view));
}

// ---- helpers ------------------------------------------------------------------------------

function objectsOf(state: State, className: string): ObjectNode[] {
  return [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === className);
}

const fieldOf = (o: ObjectNode, name: string): Value => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' };

function level(base: Level, missing: string[], contradictions: string[]): Level {
  let l = base;
  if (missing.length && RANK[l] > RANK.Possible) l = 'Possible';
  if (contradictions.length && RANK[l] > RANK.Weak) l = 'Weak';
  return l;
}

function from(a: Assessment, shape: Shape | null, view: string): Detected {
  return { key: `${a.subject}|${a.view}`, subject: a.subject, view, level: a.level, evidence: a.evidence, missing: a.missing,
    contradictions: a.contradictions, observation: a.observation, shape, auto: false };
}

function shapeOf(a: Assessment, classes: Map<string, FieldInfo[]>): Shape | null {
  const fields = classes.get(a.subject);
  if (a.view === 'tree' && fields) {
    const m = suggestTrees(new Map([[a.subject, fields]]))[0];
    return m ? { kind: 'tree', mapping: m } : null;
  }
  if (a.view === 'list' && fields) {
    const next = fields.find(f => f.javaType === a.subject);
    const value = fields.find(f => f.javaType !== a.subject);
    return next && value ? { kind: 'list', mapping: { kind: 'list', className: a.subject, value: value.name, next: next.name } } : null;
  }
  if ((a.view === 'array stack' || a.view === 'array queue') && fields) {
    const storage = fields.find(f => f.javaType.endsWith('[]'));
    const ints = fields.filter(f => INTS.has(f.javaType)).map(f => f.name);
    if (!storage || !ints.length) return null;
    return a.view === 'array stack'
      ? { kind: 'array stack', mapping: { kind: 'stack', className: a.subject, storage: storage.name, top: ints[0], topIs: 'count' } }
      : ints.length >= 2 ? { kind: 'array queue', mapping: { kind: 'queue', className: a.subject, storage: storage.name, front: ints[0], rear: ints[1], rearIs: 'next', size: ints[2] ?? null } } : null;
  }
  if (a.view === 'stack' || a.view === 'queue') return { kind: 'deque', variable: a.subject, mode: a.view };
  return null;
}

/**
 * Two links a and b of a class mirror each other when every recorded x.a = y has y.b = x and
 * every x.b = y has y.a = x, with at least one of each seen: a next/prev pair.
 */
function inversePair(state: State, nodes: ObjectNode[], links: string[]): { next: string; prev: string; checked: number } | null {
  if (links.length !== 2) return null;
  const mirrors = (a: string, b: string) => {
    let ok = 0;
    for (const x of nodes) {
      const y = fieldOf(x, a);
      if (y.kind !== 'ref') continue;
      const o = state.objects.get(y.objectId);
      if (o?.kind !== 'object') continue;
      const back = fieldOf(o, b);
      if (back.kind !== 'ref' || back.objectId !== x.objectId) return -1;
      ok++;
    }
    return ok;
  };
  const [a, b] = links;
  const ab = mirrors(a, b), ba = mirrors(b, a);
  if (ab < 1 || ba < 1) return null;
  const next = links.includes('next') ? 'next' : a;
  return { next, prev: next === a ? b : a, checked: ab + ba };
}

/** A link p whose every recorded value y has the node among y's other links: a parent pointer. */
function parentLink(state: State, nodes: ObjectNode[], links: string[]): string | null {
  for (const p of links) {
    let ok = 0, bad = false;
    for (const x of nodes) {
      const y = fieldOf(x, p);
      if (y.kind !== 'ref') continue;
      const o = state.objects.get(y.objectId);
      if (o?.kind !== 'object') { bad = true; break; }
      if (links.filter(l => l !== p).some(l => { const c = fieldOf(o, l); return c.kind === 'ref' && c.objectId === x.objectId; })) ok++;
      else { bad = true; break; }
    }
    if (!bad && ok >= 1) return p;
  }
  return null;
}

function doublyList(state: State, className: string, fields: FieldInfo[], pair: { next: string; prev: string; checked: number }, steps: string): Detected {
  const value = fields.find(f => f.javaType !== className)?.name ?? pair.next;
  const mapping: StructMapping & { kind: 'list' } = { kind: 'list', className, value, next: pair.next, prev: pair.prev };
  const { chains, conflicts } = projectList(state, mapping, [], reachable(state));
  const longest = Math.max(0, ...chains.map(c => c.nodes.length));
  const missing = objectsOf(state, className).flatMap(n => [pair.next, pair.prev].filter(f => fieldOf(n, f).kind === 'unknown').map(f => `${n.objectId}.${f} unknown`));
  return {
    key: `${className}|doubly linked list`, subject: className, view: 'doubly linked list',
    level: level(longest >= 3 ? 'Strong' : longest === 2 ? 'Possible' : 'Weak', missing, conflicts),
    evidence: [`longest chain ${longest}; ${pair.checked} ${pair.next}/${pair.prev} links mirror each other at ${steps.replace('steps 1–', 'step ')}`],
    missing, contradictions: conflicts, shape: { kind: 'list', mapping }, auto: false,
  };
}

function childrenTree(state: State, m: ChildrenMapping, fields: FieldInfo[], steps: string): Detected {
  const p = projectChildren(state, m);
  let deep = false, branching = false;
  const walk = (n: (typeof p.roots)[number]) => {
    if (n.depth >= 2) deep = true;
    if (n.kids.filter(k => k.node).length >= 2) branching = true;
    for (const k of n.kids) if (k.node) walk(k.node);
  };
  p.roots.forEach(walk);
  const trie = m.keyed && fields.some(f => f.javaType === 'boolean');
  const view = trie ? 'trie' : m.keyed ? 'tree with keyed children' : 'tree with children';
  return {
    key: `${m.className}|${view}`, subject: m.className, view,
    level: level(p.nodes >= 3 && p.links >= 2 && (deep || branching) ? 'Strong' : p.nodes >= 2 && p.links >= 1 ? 'Possible' : 'Weak', [], p.conflicts),
    evidence: [`${p.nodes} nodes, ${p.links} links through ${m.children} at ${steps.replace('steps 1–', 'step ')}`, deep || branching ? 'branching or depth ≥ 2 seen' : 'no branching or depth ≥ 2 yet'],
    missing: [], contradictions: p.conflicts, shape: { kind: 'children', mapping: m }, auto: false,
  };
}

function chains(state: State, m: ChainMapping, steps: string): Detected {
  const views = projectChains(state, m);
  const entries = views.reduce((s, v) => s + v.slots.reduce((t, x) => t + x.nodes.length, 0), 0);
  const used = views.reduce((s, v) => s + v.slots.filter(x => x.nodes.length > 0).length, 0);
  const longest = Math.max(0, ...views.flatMap(v => v.slots.map(x => x.nodes.length)));
  const conflicts = views.flatMap(v => v.conflicts);
  return {
    key: `${m.owner}|hash table with chaining`, subject: m.owner ?? m.array, view: 'hash table with chaining',
    level: level(used >= 2 && longest >= 2 ? 'Strong' : entries >= 1 ? 'Possible' : 'Weak', [], conflicts),
    evidence: [`${entries} entries in ${used} slots of ${m.array}, longest chain ${longest} at ${steps.replace('steps 1–', 'step ')}`],
    missing: [], contradictions: conflicts, shape: { kind: 'chains', mapping: m }, auto: false,
  };
}

/**
 * rank-2 heap rule: heap-shaped swaps are two writes within 3 steps at a parent and its child.
 * Strong needs at least 3 of them over at least 2 different pairs, one below the root (a bubble
 * sort swapping a[0] and a[1] again and again is not a heap). The order shown is the one that holds.
 */
function heapEvidence(arrayIds: Set<string>, ops: ObservableEvent[]): { swaps: number; pairs: Set<string>; deep: boolean; order: 'max' | 'min' | null } {
  const num = (v: Value) => v.kind === 'int' || v.kind === 'long' || v.kind === 'short' || v.kind === 'byte' ? Number(v.value) : v.kind === 'char' ? v.value : null;
  const writes = ops.flatMap(e => e.kind === 'ARRAY_WRITE' && arrayIds.has(e.data.objectId) ? [{ step: e.step, index: e.data.index, id: e.data.objectId, value: num(e.data.value) }] : []);
  let swaps = 0, deep = false, larger = 0, smaller = 0;
  const pairs = new Set<string>();
  for (let i = 0; i < writes.length; i++) {
    for (let j = i + 1; j < writes.length && writes[j].step - writes[i].step <= 2; j++) {
      if (writes[j].id !== writes[i].id) continue;
      const [a, b] = [Math.min(writes[i].index, writes[j].index), Math.max(writes[i].index, writes[j].index)];
      if (b === 2 * a + 1 || b === 2 * a + 2) {
        swaps++; pairs.add(`${a}-${b}`); if (a >= 1) deep = true;
        // The order a swap restores: the parent receives the larger value (max) or the smaller one (min).
        const atParent = writes[i].index === a ? writes[i].value : writes[j].value, atChild = writes[i].index === a ? writes[j].value : writes[i].value;
        if (atParent !== null && atChild !== null) { if (atParent > atChild) larger++; else if (atParent < atChild) smaller++; }
        i = j; break;
      }
    }
  }
  return { swaps, pairs, deep, order: larger > smaller ? 'max' : smaller > larger ? 'min' : null };
}

/** Whether a heap order holds in values[0..end): every parent not below (max) or not above (min) its children. */
function orderHolds(values: Value[], end: number, order: 'max' | 'min'): boolean {
  const n = (v: Value) => v.kind === 'int' || v.kind === 'long' || v.kind === 'short' || v.kind === 'byte' ? Number(v.value) : v.kind === 'char' ? v.value : null;
  for (let p = 0; p < end; p++) for (const c of [2 * p + 1, 2 * p + 2]) {
    if (c >= end) continue;
    const x = n(values[p]), y = n(values[c]);
    if (x === null || y === null) continue;
    if (order === 'max' ? x < y : x > y) return false;
  }
  return true;
}

function heapLevel(e: { swaps: number; pairs: Set<string>; deep: boolean; order: 'max' | 'min' | null }): Level {
  const l: Level = e.swaps >= 3 && e.pairs.size >= 2 && e.deep ? 'Strong' : e.swaps >= 1 ? 'Possible' : 'Weak';
  return l === 'Strong' && e.order === null ? 'Possible' : l; // the swaps must say which order they restore
}

function heapInClass(state: State, ops: ObservableEvent[], className: string, array: string, size: string, steps: string): Detected {
  const nodes = objectsOf(state, className);
  const ids = new Set(nodes.flatMap(n => { const v = fieldOf(n, array); return v.kind === 'ref' ? [v.objectId] : []; }));
  const e = heapEvidence(ids, ops);
  const first = nodes[0];
  const ref = first ? fieldOf(first, array) : undefined;
  const arr = ref?.kind === 'ref' ? state.objects.get(ref.objectId) : undefined;
  const count = first ? fieldOf(first, size) : undefined;
  const end = count?.kind === 'int' ? Number(count.value) : 0;
  const order = e.order ?? 'max';
  return {
    key: `${className}|heap`, subject: className, view: e.order ? `${e.order}-heap` : 'heap',
    level: heapLevel(e),
    evidence: [`${e.swaps} parent-child swaps in ${array} (${e.pairs.size} different pairs)${e.order ? `, restoring ${e.order}-heap order` : ''} in ${steps}`],
    missing: [], contradictions: [],
    observation: arr?.kind === 'array' && e.order ? `observed: ${order}-heap order ${orderHolds(arr.elements, Math.min(end, arr.elements.length), order) ? 'holds' : 'fails'} in ${array}[0..${size})` : undefined,
    shape: { kind: 'heap', mapping: { array, owner: className, base: 0, size, bound: 'count', order } }, auto: false,
  };
}

function heapVariable(array: TraceObject & { kind: 'array' }, name: string, ops: ObservableEvent[], steps: string): Detected {
  const e = heapEvidence(new Set([array.objectId]), ops);
  const order = e.order ?? 'max';
  return {
    key: `${name}|heap`, subject: name, view: e.order ? `${e.order}-heap` : 'heap',
    level: heapLevel(e),
    evidence: [`${e.swaps} parent-child swaps (${e.pairs.size} different pairs)${e.order ? `, restoring ${e.order}-heap order` : ''} in ${steps}`],
    missing: [], contradictions: [],
    observation: e.order ? `observed: ${order}-heap order ${orderHolds(array.elements, array.elements.length, order) ? 'holds' : 'fails'} (whole array)` : undefined,
    shape: { kind: 'heap', mapping: { array: name, base: 0, size: null, bound: 'count', order } }, auto: false,
  };
}
function listGraph(state: State, name: string, steps: string): Detected {
  const mapping: GraphMapping = { array: name, form: 'lists', directed: false, noEdge: -1, start: 0 };
  const p = projectGraph(state, mapping);
  if (!p || 'problem' in p) {
    return { key: `${name}|graph`, subject: name, view: 'graph', level: p ? 'Incompatible' : 'Unknown', evidence: [],
      missing: [], contradictions: p && 'problem' in p ? [p.problem] : [], shape: null, auto: false };
  }
  const g = p.graph;
  mapping.directed = !g.symmetric;
  return {
    key: `${name}|graph`, subject: name, view: g.symmetric ? 'undirected graph' : 'directed graph',
    level: g.edges.length >= 3 && g.n >= 3 ? 'Strong' : g.edges.length >= 1 ? 'Possible' : 'Weak',
    evidence: [`${g.n} nodes, ${g.edges.length} edges, every entry a node index at ${steps.replace('steps 1–', 'step ')}`],
    missing: [], contradictions: [], shape: { kind: 'graph', mapping }, auto: false,
  };
}
