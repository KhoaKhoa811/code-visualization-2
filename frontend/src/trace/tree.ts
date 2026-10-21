// Binary-tree projection of user objects (Part 9b, specs/tree-view.md). A tree view is shown
// only for a class the user confirmed, through named value/left/right fields. Suggestions come
// from recorded field types; they are never confirmed automatically. The projection reads the
// reconstructed state only and never repairs it: a node reached twice or through a cycle is
// drawn once, and the extra link is reported as a conflict.
import type { State } from './playback.ts';
import type { Trace, TraceObject, Value } from './types.ts';

export interface TreeMapping { className: string; value: string; left: string; right: string }

export interface FieldInfo { name: string; javaType: string }

type ObjectNode = TraceObject & { kind: 'object' };

/** Fields of every traced class that appears anywhere in the trace (declaration order). */
export function classFields(trace: Trace): Map<string, FieldInfo[]> {
  const out = new Map<string, FieldInfo[]>();
  const see = (o: TraceObject) => {
    if (o.kind === 'object' && !out.has(o.className)) out.set(o.className, o.fields.map(f => ({ name: f.name, javaType: f.javaType })));
  };
  for (const o of trace.initialState?.objects ?? []) see(o);
  for (const e of trace.events) {
    if ((e.kind === 'ALLOCATE' || e.kind === 'OBJECT_CAPTURE') && e.data.object) see(e.data.object);
  }
  return out;
}

/**
 * A suggestion for each class with at least two fields of its own type and one other field:
 * fields named left/right first, otherwise the first two in declaration order. A suggestion
 * is a guess about presentation only; the user confirms or corrects it.
 */
export function suggestTrees(fields: Map<string, FieldInfo[]>): TreeMapping[] {
  const out: TreeMapping[] = [];
  for (const [className, list] of fields) {
    const links = list.filter(f => f.javaType === className).map(f => f.name);
    const values = list.filter(f => f.javaType !== className).map(f => f.name);
    if (links.length < 2 || values.length === 0) continue;
    const left = links.includes('left') ? 'left' : links[0];
    const right = links.includes('right') && left !== 'right' ? 'right' : links.find(l => l !== left)!;
    out.push({ className, value: values[0], left, right });
  }
  return out;
}

/** Why a mapping does not fit a trace's class (null when it fits): checked again for every run. */
export function mappingProblem(mapping: TreeMapping, fields: Map<string, FieldInfo[]>): string | null {
  const list = fields.get(mapping.className);
  if (!list) return `no ${mapping.className} objects in this run`;
  const type = (name: string) => list.find(f => f.name === name)?.javaType;
  for (const link of [mapping.left, mapping.right]) {
    if (type(link) === undefined) return `${mapping.className} has no field ${link}`;
    if (type(link) !== mapping.className) return `${link} is not a ${mapping.className} field`;
  }
  if (mapping.left === mapping.right) return 'left and right must be different fields';
  if (type(mapping.value) === undefined) return `${mapping.className} has no field ${mapping.value}`;
  return null;
}

export type Child =
  | { kind: 'node'; node: TreeNode }
  | { kind: 'null' }
  | { kind: 'unknown' }
  | { kind: 'repeat'; objectId: string } // already drawn: a shared child or a cycle
  | { kind: 'other'; value: Value }; // a reference to something that is not a mapped node

export interface TreeNode { objectId: string; value: Value; left: Child; right: Child; depth: number }

export interface Projection {
  roots: TreeNode[];
  /** Links that make the structure not a tree, as "parent.field -> child" with object ids. */
  conflicts: string[];
}

/**
 * Trees of the mapped class in a state. Roots are the mapped objects no other mapped object links
 * to as left or right child (variables' roots first). Every object is drawn once.
 */
export function projectTree(state: State, mapping: TreeMapping, rootOrder: string[] = []): Projection {
  const nodes = [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === mapping.className);
  const byId = new Map(nodes.map(n => [n.objectId, n]));
  const field = (o: ObjectNode, name: string) => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' as const };
  const linked = new Set<string>();
  for (const n of nodes) {
    for (const name of [mapping.left, mapping.right]) {
      const v = field(n, name);
      if (v.kind === 'ref' && byId.has(v.objectId) && v.objectId !== n.objectId) linked.add(v.objectId);
    }
  }
  const rank = (id: string) => { const i = rootOrder.indexOf(id); return i < 0 ? rootOrder.length : i; };
  const candidates = nodes.filter(n => !linked.has(n.objectId)).sort((a, b) => rank(a.objectId) - rank(b.objectId));
  const placed = new Set<string>();
  const conflicts: string[] = [];
  const build = (o: ObjectNode, depth: number): TreeNode => {
    placed.add(o.objectId);
    const child = (name: string): Child => {
      const v = field(o, name);
      if (v.kind === 'null') return { kind: 'null' };
      if (v.kind === 'unknown') return { kind: 'unknown' };
      if (v.kind !== 'ref' || !byId.has(v.objectId)) return { kind: 'other', value: v };
      if (placed.has(v.objectId)) { conflicts.push(`${o.objectId}.${name} -> ${v.objectId}`); return { kind: 'repeat', objectId: v.objectId }; }
      return { kind: 'node', node: build(byId.get(v.objectId)!, depth + 1) };
    };
    const left = child(mapping.left);
    const right = child(mapping.right);
    return { objectId: o.objectId, value: field(o, mapping.value), left, right, depth };
  };
  const roots = candidates.map(o => build(o, 0));
  // Nodes only reachable through a cycle have no root: start from them so nothing is hidden.
  for (const o of nodes) if (!placed.has(o.objectId)) roots.push(build(o, 0));
  return { roots, conflicts };
}

/** In-order positions: x is the in-order index across all trees, y the depth. */
export function positions(projection: Projection): Map<string, { x: number; y: number }> {
  const out = new Map<string, { x: number; y: number }>();
  let next = 0;
  const visit = (n: TreeNode) => {
    if (n.left.kind === 'node') visit(n.left.node);
    out.set(n.objectId, { x: next++, y: n.depth });
    if (n.right.kind === 'node') visit(n.right.node);
  };
  for (const root of projection.roots) { visit(root); next++; } // a gap between trees
  return out;
}
