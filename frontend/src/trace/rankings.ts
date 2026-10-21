// View-ranking rules rank-1 (Part 10c, specs/visualization-rules.md). An assessment says how well
// the facts recorded up to cursor k fit a way of drawing a subject: Strong / Possible / Weak, or
// Incompatible / Unknown. It is a pure function of (trace prefix, cursor, candidate, rule set):
// it never reads events after step k, never scores, and never confirms or switches a view.
// Invariant observations (search-tree order, heap order) are reported beside the level.
import type { Playback, State } from './playback.ts';
import { projectTree, suggestTrees, type FieldInfo, type TreeMapping, type TreeNode } from './tree.ts';
import { projectList, suggestStructs, type StructMapping } from './structures.ts';
import { isObservable, type ObservableEvent, type TraceObject, type Value } from './types.ts';

export const RULES = 'rank-1';

export type Level = 'Strong' | 'Possible' | 'Weak' | 'Incompatible' | 'Unknown';

export interface Assessment {
  /** What is assessed: a class (Main.Node) or a variable (a). */
  subject: string;
  view: 'tree' | 'list' | 'array stack' | 'array queue' | 'max-heap' | 'min-heap' | 'stack' | 'queue';
  level: Level;
  /** Counts behind the level, each saying what it counts. */
  evidence: string[];
  missing: string[];
  contradictions: string[];
  /** An invariant observation, separate from the level (search-tree or heap order). */
  observation?: string;
}

/** Confirmed mappings take precedence over suggestions for the field roles. */
export interface Candidates { trees?: TreeMapping[]; structs?: StructMapping[] }

const ORDER: Assessment['view'][] = ['tree', 'list', 'array stack', 'array queue', 'max-heap', 'min-heap', 'stack', 'queue'];
const RANK: Record<Level, number> = { Strong: 3, Possible: 2, Weak: 1, Unknown: 0, Incompatible: -1 };
const NUMERIC = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'char']);
const INTS = new Set(['int', 'short', 'byte', 'long']);

type ObjectNode = TraceObject & { kind: 'object' };

/** Assessments at cursor k from the playback's reconstructed state and its events up to step k. */
export function assess(playback: Playback, k: number, candidates: Candidates = {}): Assessment[] {
  const view = playback.at(k);
  const state = view.beforeEnd ?? view.state;
  const ops = playback.trace.events.slice(0, playback.boundary(view.cursor)).filter(isObservable);
  return assessState(state, ops, view.cursor, candidates);
}

/** The rules over one state and the observable events that led to it (steps 1..k). */
export function assessState(state: State, ops: ObservableEvent[], k: number, candidates: Candidates = {}): Assessment[] {
  const steps = `steps 1–${k}`;
  const out: Assessment[] = [];
  const objects = [...state.objects.values()];
  const classes = new Map<string, FieldInfo[]>();
  for (const o of objects) if (o.kind === 'object' && !classes.has(o.className)) classes.set(o.className, o.fields.map(f => ({ name: f.name, javaType: f.javaType })));
  const subjects = new Set<string>([...classes.keys(), ...(candidates.trees ?? []).map(m => m.className), ...(candidates.structs ?? []).map(m => m.className)]);

  for (const className of subjects) {
    const fields = classes.get(className);
    const confirmedTree = candidates.trees?.find(m => m.className === className);
    const confirmedStruct = candidates.structs?.find(m => m.className === className);
    if (!fields) {
      if (confirmedTree) out.push(unknown(className, 'tree', steps));
      if (confirmedStruct) out.push(unknown(className, structView(confirmedStruct), steps));
      continue;
    }
    const one = new Map([[className, fields]]);
    const tree = confirmedTree ?? suggestTrees(one)[0];
    if (tree) out.push(assessTree(state, tree, fields, steps));
    const struct = confirmedStruct ?? suggestStructs(one)[0];
    if (struct?.kind === 'list') out.push(assessList(state, struct, fields, steps));
    if (struct && struct.kind !== 'list') {
      const ints = fields.filter(f => INTS.has(f.javaType)).map(f => f.name);
      const stack: StructMapping & { kind: 'stack' } = struct.kind === 'stack' ? struct
        : { kind: 'stack', className, storage: struct.storage, top: ints[0], topIs: 'count' };
      out.push(assessArrayStack(state, ops, stack, fields, steps));
      if (struct.kind === 'queue' || ints.length >= 2) {
        const queue: StructMapping & { kind: 'queue' } = struct.kind === 'queue' ? struct
          : { kind: 'queue', className, storage: struct.storage, front: ints[0], rear: ints[1], rearIs: 'next', size: ints[2] ?? null };
        out.push(assessArrayQueue(state, ops, queue, fields, steps));
      }
    }
  }

  // Variables, outermost frame first: numeric arrays (heap) and deques (stack or queue).
  const seen = new Set<string>();
  for (const frame of state.frames) {
    for (const b of state.bindings.filter(x => x.frameId === frame.frameId)) {
      if (b.value.kind !== 'ref' || seen.has(b.name)) continue;
      const target = state.objects.get(b.value.objectId);
      if (target?.kind === 'array' && NUMERIC.has(target.elementType)) {
        seen.add(b.name);
        out.push(...assessHeap(target, b.name, ops, steps));
      } else if (target?.kind === 'collection' && /\.(ArrayDeque|LinkedList)$/.test(target.className)) {
        seen.add(b.name);
        out.push(...assessDeque(target.objectId, b.name, ops, steps));
      }
    }
  }
  return out.sort((a, b) => a.subject.localeCompare(b.subject) || RANK[b.level] - RANK[a.level] || ORDER.indexOf(a.view) - ORDER.indexOf(b.view));
}

function structView(m: StructMapping): Assessment['view'] {
  return m.kind === 'list' ? 'list' : m.kind === 'stack' ? 'array stack' : 'array queue';
}

function unknown(subject: string, view: Assessment['view'], steps: string): Assessment {
  return { subject, view, level: 'Unknown', evidence: [`nothing recorded yet (${steps})`], missing: [], contradictions: [] };
}

/** Caps: a missing fact limits to Possible, a contradiction to Weak. */
function level(base: Level, missing: string[], contradictions: string[]): Level {
  let l = base;
  if (missing.length && RANK[l] > RANK.Possible) l = 'Possible';
  if (contradictions.length && RANK[l] > RANK.Weak) l = 'Weak';
  return l;
}

const typeOf = (fields: FieldInfo[], name: string) => fields.find(f => f.name === name)?.javaType;
const nodesOf = (state: State, className: string) =>
  [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === className);
const fieldOf = (o: ObjectNode, name: string): Value => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' };

function intOf(v: Value): number | null {
  return v.kind === 'int' || v.kind === 'short' || v.kind === 'byte' || v.kind === 'long' ? Number(v.value) : null;
}

function comparable(v: Value): number | string | null {
  if (v.kind === 'byte' || v.kind === 'short' || v.kind === 'int' || v.kind === 'long') return Number(v.value);
  if (v.kind === 'char') return v.value;
  return null;
}

// ---- tree ------------------------------------------------------------------------------

function assessTree(state: State, m: TreeMapping, fields: FieldInfo[], steps: string): Assessment {
  const base = { subject: m.className, view: 'tree' as const };
  for (const link of [m.left, m.right]) {
    if (typeOf(fields, link) !== m.className) {
      return { ...base, level: 'Incompatible', evidence: [], missing: [], contradictions: [`${link} is not a ${m.className} field`] };
    }
  }
  const nodes = nodesOf(state, m.className);
  const { roots, conflicts } = projectTree(state, m);
  const missing = nodes.flatMap(n => [m.value, m.left, m.right].filter(f => fieldOf(n, f).kind === 'unknown').map(f => `${n.objectId}.${f} unknown`));
  let links = 0, deep = false, twoChildren = false;
  const walk = (n: TreeNode) => {
    const kids = [n.left, n.right].filter(c => c.kind === 'node');
    links += kids.length;
    if (n.depth >= 2) deep = true;
    if (kids.length === 2) twoChildren = true;
    for (const c of kids) if (c.kind === 'node') walk(c.node);
  };
  roots.forEach(walk);
  const strong = nodes.length >= 3 && links >= 2 && (deep || twoChildren);
  const possible = nodes.length >= 2 && links >= 1;
  return {
    ...base,
    level: level(strong ? 'Strong' : possible ? 'Possible' : 'Weak', missing, conflicts),
    evidence: [`${nodes.length} nodes, ${links} links at ${steps.replace('steps 1–', 'step ')}`, deep || twoChildren ? 'branching or depth ≥ 2 seen' : 'no branching or depth ≥ 2 yet'],
    missing, contradictions: conflicts,
    observation: searchOrder(roots, nodes.length),
  };
}

/** Search-tree order on the value field: left subtree smaller, right subtree not smaller. */
function searchOrder(roots: TreeNode[], n: number): string | undefined {
  if (n < 2) return undefined;
  const fails: string[] = [];
  let unknownValue = false;
  const check = (node: TreeNode, lo: number | string | null, hi: number | string | null) => {
    const v = comparable(node.value);
    if (v === null) { unknownValue = true; return; }
    if ((lo !== null && v < lo) || (hi !== null && v >= hi)) fails.push(node.objectId);
    if (node.left.kind === 'node') check(node.left.node, lo, v);
    if (node.right.kind === 'node') check(node.right.node, v, hi);
  };
  roots.forEach(r => check(r, null, null));
  if (fails.length) return `observed: search-tree order fails at ${fails.join(', ')}`;
  if (unknownValue) return 'observed: search-tree order cannot be told (values not comparable or unknown)';
  return 'observed: search-tree order holds';
}

// ---- list ------------------------------------------------------------------------------

function assessList(state: State, m: StructMapping & { kind: 'list' }, fields: FieldInfo[], steps: string): Assessment {
  const base = { subject: m.className, view: 'list' as const };
  if (typeOf(fields, m.next) !== m.className) {
    return { ...base, level: 'Incompatible', evidence: [], missing: [], contradictions: [`${m.next} is not a ${m.className} field`] };
  }
  const nodes = nodesOf(state, m.className);
  const { chains, conflicts } = projectList(state, m);
  const missing = nodes.flatMap(n => [m.value, m.next].filter(f => fieldOf(n, f).kind === 'unknown').map(f => `${n.objectId}.${f} unknown`));
  const longest = Math.max(0, ...chains.map(c => c.nodes.length));
  return {
    ...base,
    level: level(longest >= 3 ? 'Strong' : longest === 2 ? 'Possible' : 'Weak', missing, conflicts),
    evidence: [`longest chain ${longest} of ${nodes.length} nodes at ${steps.replace('steps 1–', 'step ')}`],
    missing, contradictions: conflicts,
  };
}

// ---- stacks and queues built on an array ------------------------------------------------

/** Recorded writes to an int field of the class's objects in steps 1..k: (object, previous, value). */
function fieldWrites(ops: ObservableEvent[], ids: Set<string>, name: string): { objectId: string; from: number | null; to: number | null }[] {
  return ops.flatMap(e => e.kind === 'FIELD_WRITE' && ids.has(e.data.objectId) && e.data.field === name
    ? [{ objectId: e.data.objectId, from: intOf(e.data.previous), to: intOf(e.data.value) }] : []);
}

function storageLength(state: State, o: ObjectNode, storage: string): number | null {
  const v = fieldOf(o, storage);
  const a = v.kind === 'ref' ? state.objects.get(v.objectId) : undefined;
  return a?.kind === 'array' ? a.elements.length : null;
}

function assessArrayStack(state: State, ops: ObservableEvent[], m: StructMapping & { kind: 'stack' }, fields: FieldInfo[], steps: string): Assessment {
  const base = { subject: m.className, view: 'array stack' as const };
  if (!typeOf(fields, m.storage)?.endsWith('[]') || !INTS.has(typeOf(fields, m.top) ?? '')) {
    return { ...base, level: 'Incompatible', evidence: [], missing: [], contradictions: ['needs an array field and an int field'] };
  }
  const nodes = nodesOf(state, m.className);
  const writes = fieldWrites(ops, new Set(nodes.map(n => n.objectId)), m.top);
  const missing: string[] = [], contradictions: string[] = [];
  let push = 0, pop = 0;
  for (const w of writes) {
    if (w.from === null || w.to === null) { missing.push(`a write to ${m.top} with an unknown value`); continue; }
    if (w.to - w.from === 1) push++;
    if (w.to - w.from === -1) pop++;
  }
  for (const n of nodes) {
    const t = intOf(fieldOf(n, m.top)), len = storageLength(state, n, m.storage);
    if (t === null || len === null) { missing.push(`${n.objectId}.${m.top} or ${m.storage} unknown`); continue; }
    if (t < 0 || t > len) contradictions.push(`${n.objectId}.${m.top} = ${t} outside 0..${len}`);
  }
  const ops3 = push + pop;
  return {
    ...base,
    level: level(ops3 >= 3 && push > 0 && pop > 0 ? 'Strong' : ops3 >= 1 ? 'Possible' : 'Weak', missing, contradictions),
    evidence: [`${push} push-like, ${pop} pop-like changes of ${m.top} in ${steps}`],
    missing, contradictions,
  };
}

function assessArrayQueue(state: State, ops: ObservableEvent[], m: StructMapping & { kind: 'queue' }, fields: FieldInfo[], steps: string): Assessment {
  const base = { subject: m.className, view: 'array queue' as const };
  if (!typeOf(fields, m.storage)?.endsWith('[]') || !INTS.has(typeOf(fields, m.front) ?? '') || !INTS.has(typeOf(fields, m.rear) ?? '') || m.front === m.rear) {
    return { ...base, level: 'Incompatible', evidence: [], missing: [], contradictions: ['needs an array field and two different int fields'] };
  }
  const nodes = nodesOf(state, m.className);
  const byId = new Map(nodes.map(n => [n.objectId, n]));
  const missing: string[] = [], contradictions: string[] = [];
  const count = (name: string) => {
    let forward = 0;
    for (const w of fieldWrites(ops, new Set(byId.keys()), name)) {
      const len = storageLength(state, byId.get(w.objectId)!, m.storage);
      if (w.from === null || w.to === null || len === null || len === 0) { missing.push(`a write to ${name} with an unknown value or storage`); continue; }
      if (w.to === w.from) continue;
      if (((w.to - w.from) % len + len) % len === 1) forward++;
      else contradictions.push(`${w.objectId}.${name} moved ${w.from} → ${w.to}`);
    }
    return forward;
  };
  const enq = count(m.rear), deq = count(m.front);
  return {
    ...base,
    level: level(enq >= 2 && deq >= 1 ? 'Strong' : enq >= 1 ? 'Possible' : 'Weak', missing, contradictions),
    evidence: [`${enq} enqueue-like (${m.rear} +1), ${deq} dequeue-like (${m.front} +1) in ${steps}`],
    missing, contradictions,
  };
}

// ---- heap -------------------------------------------------------------------------------

function assessHeap(array: TraceObject & { kind: 'array' }, name: string, ops: ObservableEvent[], steps: string): Assessment[] {
  const writes = ops.flatMap(e => e.kind === 'ARRAY_WRITE' && e.data.objectId === array.objectId ? [{ step: e.step, index: e.data.index }] : []);
  let swaps = 0;
  for (let i = 0; i < writes.length; i++) {
    for (let j = i + 1; j < writes.length && writes[j].step - writes[i].step <= 2; j++) {
      const [a, b] = [Math.min(writes[i].index, writes[j].index), Math.max(writes[i].index, writes[j].index)];
      if (b === 2 * a + 1 || b === 2 * a + 2) { swaps++; i = j; break; } // each write pairs at most once
    }
  }
  const missing = array.elements.some(v => v.kind === 'unknown') ? [`${name} has unknown elements`] : [];
  const base = level(swaps >= 3 ? 'Strong' : swaps >= 1 ? 'Possible' : 'Weak', missing, []);
  const evidence = [`${swaps} parent-child swaps (0-based) in ${steps}`];
  const orderOf = (max: boolean) => {
    const fails: string[] = [];
    for (let p = 0; p < array.elements.length; p++) {
      for (const c of [2 * p + 1, 2 * p + 2]) {
        if (c >= array.elements.length) continue;
        const x = comparable(array.elements[p]) ?? numberBits(array.elements[p]), y = comparable(array.elements[c]) ?? numberBits(array.elements[c]);
        if (x === null || y === null) continue;
        if (max ? x < y : x > y) fails.push(`${p}→${c}`);
      }
    }
    return fails.length ? `observed: ${max ? 'max' : 'min'}-heap order fails at ${fails.join(', ')} (whole array)` : `observed: ${max ? 'max' : 'min'}-heap order holds (whole array)`;
  };
  return [
    { subject: name, view: 'max-heap', level: base, evidence, missing, contradictions: [], observation: orderOf(true) },
    { subject: name, view: 'min-heap', level: base, evidence, missing, contradictions: [], observation: orderOf(false) },
  ];
}

function numberBits(v: Value): number | null {
  if (v.kind !== 'float' && v.kind !== 'double') return null;
  const view = new DataView(new ArrayBuffer(8));
  if (v.bits.length === 8) { view.setUint32(0, parseInt(v.bits, 16)); return view.getFloat32(0); }
  view.setBigUint64(0, BigInt(`0x${v.bits}`));
  return view.getFloat64(0);
}

// ---- deque ------------------------------------------------------------------------------

const STACK_CALLS = new Set(['push', 'pop', 'peek', 'addFirst', 'removeFirst', 'pollFirst', 'peekFirst']);
const QUEUE_CALLS = new Set(['offer', 'add', 'addLast', 'poll', 'remove', 'peek']);

function assessDeque(id: string, name: string, ops: ObservableEvent[], steps: string): Assessment[] {
  let stack = 0, queue = 0, stackOnly = 0, queueOnly = 0;
  for (const e of ops) {
    if (e.kind !== 'LIBRARY' || e.data.objectId !== id) continue;
    const method = e.data.method;
    const call = method.slice(method.lastIndexOf('.', method.indexOf('(')) + 1, method.indexOf('('));
    if (call === 'remove' && e.data.arguments.length > 0) continue; // remove(Object) is neither
    if (STACK_CALLS.has(call)) { stack++; if (!QUEUE_CALLS.has(call)) stackOnly++; }
    if (QUEUE_CALLS.has(call)) { queue++; if (!STACK_CALLS.has(call)) queueOnly++; }
  }
  const lv = (mine: number, otherOnly: number): Level => mine >= 3 && otherOnly === 0 ? 'Strong' : mine >= 1 ? 'Possible' : 'Weak';
  return [
    { subject: name, view: 'stack', level: lv(stack, queueOnly), evidence: [`${stack} stack calls, ${queueOnly} queue-only calls in ${steps}`], missing: [], contradictions: [] },
    { subject: name, view: 'queue', level: lv(queue, stackOnly), evidence: [`${queue} queue calls, ${stackOnly} stack-only calls in ${steps}`], missing: [], contradictions: [] },
  ];
}
