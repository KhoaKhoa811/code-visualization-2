// Part 11b: index pointers. A variable is drawn as an arrow under an array only after the program
// has indexed that array with it: a recorded read or write whose own code is literally `…[name]`.
// Only accesses up to the cursor count (no future facts), and the arrow sits at the variable's
// current value. Nothing is inferred from names, values or the algorithm.
import type { Playback, State } from './playback.ts';
import type { TraceObject, Value } from './types.ts';

/** last: the step of the latest access to this array with this variable (12d: which grid row shows a column arrow). */
export interface Pointer { name: string; frameId: string; value: number; last: number }

interface Access { step: number; frameId: string; name: string; arrayId: string }

const INDEX = /\[\s*([A-Za-z_$][\w$]*)\s*\]$/;
/** 14c: a list read written literally `….get(name)` indexes it the same way (`adj.get(u)`). */
const GET = /\.get\(\s*([A-Za-z_$][\w$]*)\s*\)$/;
const cache = new WeakMap<Playback, Access[]>();

/**
 * 12d: a write's recorded range is its whole assignment (`g[r][c] = v`, `a[i] += 2`, `a[i]++`);
 * its index is the target's, so the target is matched. A read's range is the access itself.
 */
const ASSIGNED = /^([^=]*\])\s*(?:[-+*/%&|^]|<<|>>>?)?=(?!=)/;
const STEPPED = /^(?:(?:\+\+|--)\s*)?(.*\])(?:\s*(?:\+\+|--))?$/;
function target(code: string): string {
  return ASSIGNED.exec(code)?.[1] ?? STEPPED.exec(code.trim())?.[1] ?? code;
}

/** Every recorded array access whose index expression is a plain variable, in step order. */
function accesses(playback: Playback): Access[] {
  let found = cache.get(playback);
  if (found) return found;
  found = [];
  const text = playback.trace.source.text;
  for (const e of playback.trace.events) {
    if (e.kind === 'LIBRARY' && e.data.objectId && e.data.method.endsWith('.get(int)')) {
      const got = GET.exec(text.slice(e.source.start, e.source.end));
      if (got) found.push({ step: e.step, frameId: e.frameId, name: got[1], arrayId: e.data.objectId });
      continue;
    }
    if ((e.kind !== 'ARRAY_READ' && e.kind !== 'ARRAY_WRITE') || !('step' in e)) continue;
    const match = INDEX.exec(target(text.slice(e.source.start, e.source.end)));
    if (match) found.push({ step: e.step, frameId: e.frameId, name: match[1], arrayId: e.data.objectId });
  }
  cache.set(playback, found);
  return found;
}

const intValue = (v: Value): number | null =>
  v.kind === 'int' || v.kind === 'short' || v.kind === 'byte' ? Number(v.value) : v.kind === 'char' ? v.value : null;

/** The index pointers of each array in {@code state}, the state at cursor {@code k}. */
export function pointersAt(playback: Playback, k: number, state: State): Map<string, Pointer[]> {
  const out = new Map<string, Pointer[]>();
  const seen = new Map<string, Pointer | null>();
  for (const a of accesses(playback)) {
    if (a.step > k) break;
    const key = `${a.arrayId}|${a.frameId}|${a.name}`;
    if (seen.has(key)) { const known = seen.get(key); if (known) known.last = a.step; continue; }
    const binding = state.bindings.find(b => b.frameId === a.frameId && b.name === a.name);
    const value = binding ? intValue(binding.value) : null;
    const kind = state.objects.get(a.arrayId)?.kind;
    if (value === null || kind !== 'array' && kind !== 'collection') { seen.set(key, null); continue; }
    const pointer = { name: a.name, frameId: a.frameId, value, last: a.step };
    seen.set(key, pointer);
    const list = out.get(a.arrayId) ?? [];
    list.push(pointer);
    out.set(a.arrayId, list);
  }
  return out;
}

/**
 * 12d: in a grid, the row each variable indexed last (by recorded step), keyed by row object.
 * `g[i][j]` records a read of row g[i] written `…[j]`; only that row shows the `j` arrow.
 */
export function latestRows(grid: TraceObject & { kind: 'array' }, all: Map<string, Pointer[]>): Map<string, Pointer[]> {
  const best = new Map<string, { row: string; pointer: Pointer }>();
  for (const e of grid.elements) {
    if (e.kind !== 'ref') continue;
    for (const p of all.get(e.objectId) ?? []) {
      const key = `${p.frameId}|${p.name}`;
      const known = best.get(key);
      if (!known || p.last > known.pointer.last) best.set(key, { row: e.objectId, pointer: p });
    }
  }
  const out = new Map<string, Pointer[]>();
  for (const { row, pointer } of best.values()) out.set(row, [...(out.get(row) ?? []), pointer]);
  return out;
}