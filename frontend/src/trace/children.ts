// Part 19c-19d: trees whose children are kept in a field holding several of them: an array
// (TrieNode[] kids), a list (List<Node> children) or a map (Map<Character, Node> next). Edges
// carry their slot: an array index, a list position or a map key. Read from the recorded state
// only; a node reached twice or through a cycle is drawn once and reported, never repaired.
import type { State } from './playback.ts';
import type { TraceObject, Value } from './types.ts';
import type { FieldInfo } from './tree.ts';

export interface ChildrenMapping {
  className: string;
  /** The field holding the children. */
  children: string;
  /** A field shown in each node (its value or flag), or null. */
  value: string | null;
  /** Children stored by key (array slot or map key, as in a trie) or in order (a list). */
  keyed: boolean;
}

type ObjectNode = TraceObject & { kind: 'object' };

export interface ChildNode {
  objectId: string;
  value: Value | null;
  depth: number;
  /** Children in slot order, each with its slot label (index or key). */
  kids: { label: string; node: ChildNode | null; repeat?: string }[];
}

/** The holder types of a field that keeps several children of class C, and whether they are keyed. */
export function childrenField(className: string, javaType: string): { keyed: boolean } | null {
  if (javaType === `${className}[]`) return { keyed: true };
  const g = /^java\.util\.(\w+)<(.+)>$/.exec(javaType);
  if (!g) return null;
  const args = g[2];
  if (/^(List|ArrayList|LinkedList|Deque|ArrayDeque|Set|HashSet|LinkedHashSet)$/.test(g[1]) && args === className) return { keyed: false };
  if (/^(Map|HashMap|TreeMap|LinkedHashMap)$/.test(g[1]) && args.endsWith(`,${className}`)) return { keyed: true };
  return null;
}

/** A suggestion for each class with a field holding several of its own objects. */
export function suggestChildren(fields: Map<string, FieldInfo[]>): ChildrenMapping[] {
  const out: ChildrenMapping[] = [];
  for (const [className, list] of fields) {
    for (const f of list) {
      const kind = childrenField(className, f.javaType);
      if (!kind) continue;
      const plain = list.find(x => x.name !== f.name && !x.javaType.includes(className));
      out.push({ className, children: f.name, value: plain?.name ?? null, keyed: kind.keyed });
      break;
    }
  }
  return out;
}

const fieldOf = (o: ObjectNode, name: string): Value => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' };

/** The child slots of a node: [label, value] for every non-null slot of the children field. */
export function slots(state: State, node: ObjectNode, m: ChildrenMapping): { label: string; value: Value }[] {
  const holder = fieldOf(node, m.children);
  if (holder.kind !== 'ref') return [];
  const h = state.objects.get(holder.objectId);
  if (!h) return [];
  if (h.kind === 'array' || h.kind === 'collection') {
    return h.elements.flatMap((v, i) => v.kind === 'null' ? [] : [{ label: String(i), value: v }]);
  }
  if (h.kind === 'map') {
    return h.entries.map(e => ({ label: label(e.key), value: e.value }));
  }
  return [];
}

function label(v: Value): string {
  if (v.kind === 'char') return String.fromCharCode(v.value);
  if (v.kind === 'int' || v.kind === 'long' || v.kind === 'short' || v.kind === 'byte') return v.value;
  if (v.kind === 'boolean') return String(v.value);
  return '•';
}

export interface ChildrenProjection { roots: ChildNode[]; conflicts: string[]; nodes: number; links: number }

/** Trees of the class: roots are nodes no other node holds as a child (variables' roots first). */
export function projectChildren(state: State, m: ChildrenMapping, rootOrder: string[] = []): ChildrenProjection {
  const nodes = [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === m.className);
  const byId = new Map(nodes.map(n => [n.objectId, n]));
  const held = new Set<string>();
  let links = 0;
  for (const n of nodes) for (const s of slots(state, n, m)) if (s.value.kind === 'ref' && byId.has(s.value.objectId)) { held.add(s.value.objectId); links++; }
  const rank = (id: string) => { const i = rootOrder.indexOf(id); return i < 0 ? rootOrder.length : i; };
  const placed = new Set<string>();
  const conflicts: string[] = [];
  const build = (o: ObjectNode, depth: number): ChildNode => {
    placed.add(o.objectId);
    const kids = slots(state, o, m).map(s => {
      if (s.value.kind !== 'ref' || !byId.has(s.value.objectId)) return { label: s.label, node: null };
      if (placed.has(s.value.objectId)) { conflicts.push(`${o.objectId}.${m.children}[${s.label}] -> ${s.value.objectId}`); return { label: s.label, node: null, repeat: s.value.objectId }; }
      return { label: s.label, node: build(byId.get(s.value.objectId)!, depth + 1) };
    });
    return { objectId: o.objectId, value: m.value ? fieldOf(o, m.value) : null, depth, kids };
  };
  const roots = nodes.filter(n => !held.has(n.objectId)).sort((a, b) => rank(a.objectId) - rank(b.objectId)).map(n => build(n, 0));
  for (const n of nodes) if (!placed.has(n.objectId)) roots.push(build(n, 0)); // only reachable through a cycle
  return { roots, conflicts, nodes: nodes.length, links };
}

/** Layered positions: leaves get consecutive columns, a parent sits over the middle of its children. */
export function childPositions(p: ChildrenProjection): Map<string, { x: number; y: number }> {
  const out = new Map<string, { x: number; y: number }>();
  let next = 0;
  const place = (n: ChildNode): number => {
    const xs = n.kids.flatMap(k => k.node ? [place(k.node)] : []);
    const x = xs.length ? (xs[0] + xs[xs.length - 1]) / 2 : next++;
    out.set(n.objectId, { x, y: n.depth });
    return x;
  };
  for (const r of p.roots) { place(r); next++; }
  return out;
}
