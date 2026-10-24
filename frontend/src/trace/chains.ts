// Part 19c-19d: an array whose slots start linked chains (Entry[] buckets of a hash table with
// chaining). The array is a field of a holder object, or a variable. Each slot's chain follows the
// node class's link field as recorded; cycles and shared nodes are reported, never repaired.
import type { State } from './playback.ts';
import type { TraceObject, Value } from './types.ts';

export interface ChainMapping {
  /** The holder class whose field is the array, or null for a variable. */
  owner: string | null;
  /** The array: the holder's field, or the variable's name. */
  array: string;
  nodeClass: string;
  next: string;
  /** Fields shown in each node (key and value), in order. */
  shown: string[];
}

type ObjectNode = TraceObject & { kind: 'object' };

export interface ChainsView {
  /** The holder object (or null for a variable) and the array. */
  holderId: string | null;
  arrayId: string;
  /** Per slot, the chain's nodes in link order. */
  slots: { index: number; nodes: { objectId: string; shown: Value[] }[]; end: 'null' | 'unknown' | 'repeat' | 'other' }[];
  conflicts: string[];
}

const fieldOf = (o: ObjectNode, name: string): Value => o.fields.find(f => f.name === name)?.value ?? { kind: 'unknown' };

function lookup(state: State, name: string): Value | undefined {
  for (const frame of state.frames) {
    const b = state.bindings.find(x => x.frameId === frame.frameId && x.name === name);
    if (b) return b.value;
  }
  return undefined;
}

/** The arrays of the mapping in this state: one per holder object (or the variable's). */
export function projectChains(state: State, m: ChainMapping): ChainsView[] {
  const arrays: { holderId: string | null; ref: Value }[] = m.owner
    ? [...state.objects.values()].filter((o): o is ObjectNode => o.kind === 'object' && o.className === m.owner)
      .map(o => ({ holderId: o.objectId, ref: fieldOf(o, m.array) }))
    : [{ holderId: null, ref: lookup(state, m.array) ?? { kind: 'unknown' } }];
  const out: ChainsView[] = [];
  for (const { holderId, ref } of arrays) {
    if (ref.kind !== 'ref') continue;
    const array = state.objects.get(ref.objectId);
    if (array?.kind !== 'array') continue;
    const conflicts: string[] = [];
    const seen = new Set<string>();
    const slots = array.elements.map((head, index) => {
      const nodes: { objectId: string; shown: Value[] }[] = [];
      let at: Value = head;
      let end: 'null' | 'unknown' | 'repeat' | 'other' = 'null';
      while (true) {
        if (at.kind === 'null') { end = 'null'; break; }
        if (at.kind === 'unknown') { end = 'unknown'; break; }
        const o = at.kind === 'ref' ? state.objects.get(at.objectId) : undefined;
        if (!o || o.kind !== 'object' || o.className !== m.nodeClass) { end = 'other'; break; }
        if (seen.has(o.objectId)) { conflicts.push(`slot ${index} reaches ${o.objectId} again`); end = 'repeat'; break; }
        seen.add(o.objectId);
        nodes.push({ objectId: o.objectId, shown: m.shown.map(f => fieldOf(o, f)) });
        at = fieldOf(o, m.next);
      }
      return { index, nodes, end };
    });
    out.push({ holderId, arrayId: array.objectId, slots, conflicts });
  }
  return out;
}
