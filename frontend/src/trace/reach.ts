// Part 19c: objects the program can still reach from the variables of its frames, through fields,
// array and collection elements and map entries. An object unlinked from every structure stays in
// the recorded state with its old links; structure views and their levels look at reachable ones.
import type { State } from './playback.ts';
import type { Value } from './types.ts';

export function reachable(state: State): Set<string> {
  const seen = new Set<string>();
  const queue: string[] = [];
  const see = (v: Value) => { if (v.kind === 'ref' && !seen.has(v.objectId)) { seen.add(v.objectId); queue.push(v.objectId); } };
  for (const b of state.bindings) see(b.value);
  while (queue.length) {
    const o = state.objects.get(queue.pop()!);
    if (!o) continue;
    if (o.kind === 'object') o.fields.forEach(f => see(f.value));
    else if (o.kind === 'array' || o.kind === 'collection') o.elements.forEach(see);
    else if (o.kind === 'map') o.entries.forEach(e => { see(e.key); see(e.value); });
  }
  return seen;
}
/**
 * Part 19e: where else a node is held: a map entry (map[1]), an array slot (buckets[2]) or a field
 * of an object of another class (LRU.head). Links between objects of the node's own class, and
 * the arrays, lists and maps such objects keep their children in, are the structure itself and
 * are drawn as arrows instead. Holders are named by a variable when one refers to them.
 */
export function heldBy(state: State, objectId: string, limit = 3): string[] {
  const target = state.objects.get(objectId);
  if (target?.kind !== 'object') return [];
  const own = target.className;
  // Collections and arrays a same-class object holds (children lists, kids arrays) are internal.
  const internal = new Set<string>();
  for (const o of state.objects.values()) {
    if (o.kind === 'object' && o.className === own) for (const f of o.fields) if (f.value.kind === 'ref') internal.add(f.value.objectId);
  }
  const name = (id: string) => state.bindings.find(b => b.value.kind === 'ref' && b.value.objectId === id)?.name;
  const out: string[] = [];
  const label = (v: Value) => v.kind === 'int' || v.kind === 'long' || v.kind === 'short' || v.kind === 'byte' ? v.value
    : v.kind === 'char' ? `'${String.fromCharCode(v.value)}'` : v.kind === 'boolean' ? String(v.value) : '…';
  for (const o of state.objects.values()) {
    if (out.length >= limit) break;
    if (o.kind === 'object') {
      if (o.className === own) continue;
      for (const f of o.fields) if (f.value.kind === 'ref' && f.value.objectId === objectId) out.push(`${name(o.objectId) ?? o.className.slice(o.className.lastIndexOf('.') + 1)}.${f.name}`);
    } else if (!internal.has(o.objectId)) {
      const holder = name(o.objectId) ?? ownerField(state, o.objectId);
      if (!holder) continue;
      if (o.kind === 'map') { for (const e of o.entries) if (e.value.kind === 'ref' && e.value.objectId === objectId) out.push(`${holder}[${label(e.key)}]`); }
      else if (o.kind === 'array' || o.kind === 'collection') o.elements.forEach((v, i) => { if (v.kind === 'ref' && v.objectId === objectId) out.push(`${holder}[${i}]`); });
    }
  }
  return out.slice(0, limit);
}

/** The field that holds a collection or array (map in LRU.map), for naming it. */
function ownerField(state: State, id: string): string | undefined {
  for (const o of state.objects.values()) {
    if (o.kind !== 'object') continue;
    const f = o.fields.find(x => x.value.kind === 'ref' && x.value.objectId === id);
    if (f) return f.name;
  }
  return undefined;
}