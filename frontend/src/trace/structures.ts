// User-defined linked lists, stacks and queues (Part 10b, specs/custom-mappings.md). Like trees
// (Part 9b), a class is drawn this way only after the user confirms which fields play which role.
// Suggestions come from recorded field types only. Projections read the reconstructed state and
// never repair it: cycles, shared nodes and out-of-range indices are reported as recorded.
import type { FieldInfo } from './tree.ts';
import type { State } from './playback.ts';
import type { TraceObject, Value } from './types.ts';

export type StructMapping =
  /** prev (Part 19c): the link that mirrors next, for a doubly linked list. */
  | { kind: 'list'; className: string; value: string; next: string; prev?: string }
  /** top is the number of elements (count) or the index of the top element (index). */
  | { kind: 'stack'; className: string; storage: string; top: string; topIs: 'count' | 'index' }
  /** rear is the next free slot (next) or the last element (last); size, when mapped, decides empty versus full. */
  | { kind: 'queue'; className: string; storage: string; front: string; rear: string; rearIs: 'next' | 'last'; size: string | null };

/** A confirmed mapping with its revision and the source it was confirmed for (AC-30). */
export type Confirmed<M> = M & { revision: number; confirmedFor: string };

type ObjectNode = TraceObject & { kind: 'object' };

const INTS = new Set(['int', 'short', 'byte', 'long']);

/** Suggestions: one link field of the class's own type plus a value field is a list node;
 * an array field plus int fields may be a stack (one int) or a queue (two or more ints). */
export function suggestStructs(fields: Map<string, FieldInfo[]>): StructMapping[] {
  const out: StructMapping[] = [];
  for (const [className, list] of fields) {
    const links = list.filter(f => f.javaType === className);
    const others = list.filter(f => f.javaType !== className);
    if (links.length === 1 && others.length > 0) {
      out.push({ kind: 'list', className, value: others[0].name, next: links[0].name });
      continue;
    }
    if (links.length > 0) continue; // two links: a tree suggestion (Part 9b)
    const arrays = list.filter(f => f.javaType.endsWith('[]'));
    const ints = list.filter(f => INTS.has(f.javaType));
    if (arrays.length !== 1 || ints.length === 0) continue;
    const storage = arrays[0].name;
    if (ints.length === 1) out.push({ kind: 'stack', className, storage, top: ints[0].name, topIs: 'count' });
    else out.push({ kind: 'queue', className, storage, front: ints[0].name, rear: ints[1].name, rearIs: 'next', size: ints[2]?.name ?? null });
  }
  return out;
}

/** Why a mapping does not fit a run's recorded fields (null when it fits); checked for every run (AC-30). */
export function structProblem(m: StructMapping, fields: Map<string, FieldInfo[]>): string | null {
  const list = fields.get(m.className);
  if (!list) return `no ${m.className} objects in this run`;
  const type = (name: string) => list.find(f => f.name === name)?.javaType;
  const need = (name: string, ok: (t: string) => boolean, what: string) =>
    type(name) === undefined ? `${m.className} has no field ${name}` : ok(type(name)!) ? null : `${name} is not ${what}`;
  const isInt = (t: string) => INTS.has(t);
  const checks: (string | null)[] =
    m.kind === 'list' ? [need(m.value, () => true, ''), need(m.next, t => t === m.className, `a ${m.className} field`)]
      : m.kind === 'stack' ? [need(m.storage, t => t.endsWith('[]'), 'an array field'), need(m.top, isInt, 'an int field')]
        : [need(m.storage, t => t.endsWith('[]'), 'an array field'), need(m.front, isInt, 'an int field'), need(m.rear, isInt, 'an int field'),
          m.size === null ? null : need(m.size, isInt, 'an int field'),
          m.front === m.rear ? 'front and rear must be different fields' : null];
  return checks.find(c => c !== null) ?? null;
}

const field = (o: ObjectNode, name: string): Value => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' };

function intOf(v: Value): number | null {
  return v.kind === 'int' || v.kind === 'short' || v.kind === 'byte' || v.kind === 'long' ? Number(v.value) : null;
}

function objectsOf(state: State, className: string): ObjectNode[] {
  return [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === className);
}

// ---- lists ------------------------------------------------------------------------------

export interface Chain {
  nodes: { objectId: string; value: Value }[];
  /** How the chain ends: null, unknown, a reference to something else, or a link back to a node already drawn. */
  end: { kind: 'null' } | { kind: 'unknown' } | { kind: 'other'; value: Value } | { kind: 'repeat'; objectId: string };
}

/** Chains from the nodes no other node links to (variables' first); a cycle-only group still shows. */
export function projectList(state: State, m: StructMapping & { kind: 'list' }, rootOrder: string[] = [], live?: Set<string>): { chains: Chain[]; conflicts: string[] } {
  // Part 19c: with live, only nodes the program can still reach (an unlinked node keeps stale links).
  const nodes = objectsOf(state, m.className).filter(n => !live || live.has(n.objectId));
  const byId = new Map(nodes.map(n => [n.objectId, n]));
  const incoming = new Map<string, string[]>();
  for (const n of nodes) {
    const v = field(n, m.next);
    if (v.kind === 'ref' && byId.has(v.objectId)) incoming.set(v.objectId, [...(incoming.get(v.objectId) ?? []), n.objectId]);
  }
  const conflicts = [...incoming.entries()].filter(([, from]) => from.length > 1)
    .map(([to, from]) => `${to} is next of ${from.join(' and ')}`);
  const rank = (id: string) => { const i = rootOrder.indexOf(id); return i < 0 ? rootOrder.length : i; };
  const starts = nodes.filter(n => !incoming.has(n.objectId)).sort((a, b) => rank(a.objectId) - rank(b.objectId));
  const placed = new Set<string>();
  const walk = (start: ObjectNode): Chain => {
    const chain: Chain = { nodes: [], end: { kind: 'null' } };
    let at: ObjectNode | undefined = start;
    while (at) {
      placed.add(at.objectId);
      chain.nodes.push({ objectId: at.objectId, value: field(at, m.value) });
      const v = field(at, m.next);
      if (v.kind === 'null') { chain.end = { kind: 'null' }; break; }
      if (v.kind === 'unknown') { chain.end = { kind: 'unknown' }; break; }
      if (v.kind !== 'ref' || !byId.has(v.objectId)) { chain.end = { kind: 'other', value: v }; break; }
      if (placed.has(v.objectId)) { chain.end = { kind: 'repeat', objectId: v.objectId }; break; }
      at = byId.get(v.objectId);
    }
    return chain;
  };
  const chains = starts.map(walk);
  for (const n of nodes) if (!placed.has(n.objectId)) chains.push(walk(n)); // a pure cycle has no start
  for (const c of chains) if (c.end.kind === 'repeat') conflicts.push(`cycle back to ${c.end.objectId}`);
  return { chains, conflicts };
}

// ---- stacks and queues backed by an array --------------------------------------------

export interface ArrayView {
  objectId: string;
  /** The storage array and its elements, or null when the storage is not a known array. */
  storageId: string | null;
  cells: Value[] | null;
  /** Indices holding elements, in logical order (bottom to top, or front to rear). */
  occupied: number[];
  /** Explanations when the recorded fields do not determine the contents. */
  notes: string[];
  front?: number; rear?: number; top?: number;
}

function storageOf(state: State, o: ObjectNode, name: string): { storageId: string | null; cells: Value[] | null } {
  const v = field(o, name);
  const a = v.kind === 'ref' ? state.objects.get(v.objectId) : undefined;
  return a?.kind === 'array' ? { storageId: a.objectId, cells: a.elements } : { storageId: null, cells: null };
}

export function projectStack(state: State, m: StructMapping & { kind: 'stack' }): ArrayView[] {
  return objectsOf(state, m.className).map(o => {
    const { storageId, cells } = storageOf(state, o, m.storage);
    const t = intOf(field(o, m.top));
    const notes: string[] = [];
    if (!cells) return { objectId: o.objectId, storageId, cells, occupied: [], notes: [`${m.storage} is not a known array`] };
    if (t === null) return { objectId: o.objectId, storageId, cells, occupied: [], notes: [`${m.top} is unknown`] };
    const count = m.topIs === 'count' ? t : t + 1;
    if (count < 0 || count > cells.length) notes.push(`${m.top} = ${t} is outside the storage (length ${cells.length}); shown as recorded`);
    const occupied = Array.from({ length: Math.max(0, Math.min(count, cells.length)) }, (_, i) => i);
    return { objectId: o.objectId, storageId, cells, occupied, notes, top: count - 1 };
  });
}

export function projectQueue(state: State, m: StructMapping & { kind: 'queue' }): ArrayView[] {
  return objectsOf(state, m.className).map(o => {
    const { storageId, cells } = storageOf(state, o, m.storage);
    const f = intOf(field(o, m.front)), r = intOf(field(o, m.rear));
    if (!cells) return { objectId: o.objectId, storageId, cells, occupied: [], notes: [`${m.storage} is not a known array`] };
    if (f === null || r === null) return { objectId: o.objectId, storageId, cells, occupied: [], notes: ['front or rear is unknown'] };
    const n = cells.length;
    const notes: string[] = [];
    if (f < 0 || f >= Math.max(1, n) || r < 0 || r >= Math.max(1, n) + (m.rearIs === 'next' ? 1 : 0)) {
      notes.push(`front ${f} or rear ${r} is outside the storage (length ${n}); shown as recorded`);
      return { objectId: o.objectId, storageId, cells, occupied: [], notes, front: f, rear: r };
    }
    let count: number | null;
    if (m.size !== null) {
      count = intOf(field(o, m.size));
      if (count === null) notes.push(`${m.size} is unknown`);
    } else {
      const next = m.rearIs === 'next' ? r % Math.max(1, n) : (r + 1) % Math.max(1, n);
      const span = (next - f + n) % Math.max(1, n);
      // Without a size field, front meeting rear means empty or full; the fields cannot tell which.
      count = span === 0 ? null : span;
      if (span === 0) notes.push('front meets rear: empty or full cannot be told without a size field');
    }
    const occupied = count === null ? [] : Array.from({ length: Math.max(0, Math.min(count, n)) }, (_, i) => (f + i) % n);
    return { objectId: o.objectId, storageId, cells, occupied, notes, front: f, rear: r };
  });
}
