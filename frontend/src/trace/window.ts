// Part 25 (specs/sliding-window.md): sliding windows. A step that puts one cell of an array or a
// string into an aggregate (`sum += a[r]`, `seen.add(s.charAt(r))`, `freq[a[r]]++`) makes that cell
// enter; a step that takes one back out (`sum -= a[l]`, `seen.remove(s.charAt(l))`, `freq[..]--`)
// makes it leave. The window is the cells that entered and have not left. It is shown only after a
// cell has left (before that, a running total is not yet a window) and only while its cells are
// contiguous. Only recorded steps up to the cursor are used.
import type { Playback, State } from './playback.ts';
import type { ObservableEvent, Value } from './types.ts';
import { plainVariables, readsOf } from './flows.ts';
import { namesOf } from './format.ts';

/** One window over the array or string `objectId`: cells lo..hi, named after its aggregate. */
export interface SlidingWindow {
  objectId: string;
  lo: number;
  hi: number;
  /** The aggregate's name and, for a variable, its value now (`sum = 9`). */
  label: string;
  /** The cell that entered or left at the cursor's step, if any. */
  entered: number | null;
  left: number | null;
}

interface Move { step: number; key: string; holder: Holder; source: string; index: number; dir: 'in' | 'out' }
type Holder = { binding: string } | { object: string };

const IN_CALLS = new Set(['add', 'offer', 'addLast', 'addFirst', 'offerLast', 'offerFirst', 'push']);
const OUT_CALLS = new Set(['remove', 'poll', 'pollFirst', 'pollLast', 'removeFirst', 'removeLast', 'pop']);
const cache = new WeakMap<Playback, Move[]>();

/** In or out, from the step's own code and kind; null when the step does not add or take away. */
function direction(e: ObservableEvent, code: string): 'in' | 'out' | null {
  const flat = code.replace(/\s+/g, '');
  if (e.kind === 'ASSIGN' || e.kind === 'ARRAY_WRITE') {
    if (/^[^=]*\+=/.test(flat) || /^[^=]*\+\+$/.test(flat) || /^\+\+/.test(flat)) return 'in';
    if (/^[^=]*-=/.test(flat) || /^[^=]*--$/.test(flat) || /^--/.test(flat)) return 'out';
    // x = x + a[i] / x = x - a[i]
    const m = /^([\w$.[\]]+)=\1([+-])/.exec(flat);
    return m ? (m[2] === '+' ? 'in' : 'out') : null;
  }
  if (e.kind === 'UPDATE') return e.data.operator === '++' ? 'in' : 'out';
  if (e.kind === 'LIBRARY' && e.data.objectId) {
    const method = e.data.method.slice(0, e.data.method.indexOf('('));
    const name = method.slice(method.lastIndexOf('.') + 1);
    if (IN_CALLS.has(name)) return 'in';
    if (OUT_CALLS.has(name)) return 'out';
    // A count kept in a map: put(c, get(c) + 1), merge(c, 1, Integer::sum); a - 1 takes away.
    if (name === 'put' || name === 'merge') return /-\s*1\b|,\s*-1\b/.test(code) ? 'out' : 'in';
  }
  return null;
}

function holderOf(e: ObservableEvent): Holder | null {
  if (e.kind === 'ASSIGN' || e.kind === 'UPDATE') return { binding: e.data.bindingId };
  if (e.kind === 'ARRAY_WRITE') return { object: e.data.objectId };
  if (e.kind === 'LIBRARY' && e.data.objectId) return { object: e.data.objectId };
  return null;
}

/** Every recorded step that moves one cell into or out of an aggregate, in step order. */
function moves(playback: Playback): Move[] {
  let found = cache.get(playback);
  if (found) return found;
  found = [];
  const events = playback.trace.events, text = playback.trace.source.text;
  // A variable that holds one cell's value (char c = s.charAt(r)): by frame and name, the cell it was read from.
  const names = new Map<string, string>(), held = new Map<string, { objectId: string; index: number }>();
  events.forEach((e, at) => {
    if (!('step' in e)) return;
    if (e.kind === 'DECLARE') names.set(e.data.binding.bindingId, e.data.binding.name);
    if (e.kind === 'DECLARE' || e.kind === 'ASSIGN') {
      const name = names.get(e.kind === 'DECLARE' ? e.data.binding.bindingId : e.data.bindingId);
      const read = readsOf(events, at, e.source.start).flatMap(a => 'cell' in a ? [a.cell] : []);
      if (name) { const key = `${e.frameId}|${name}`; if (read.length === 1) held.set(key, read[0]); else held.delete(key); }
    }
    const holder = holderOf(e);
    if (!holder) return;
    const dir = direction(e, text.slice(e.source.start, e.source.end));
    if (!dir) return;
    const own = 'object' in holder ? holder.object : null;
    // The one cell of another array or string the statement read (a counter's own cell is not one).
    const code = text.slice(e.source.start, e.source.end);
    let cells = readsOf(events, at, e.source.start).flatMap(a => 'cell' in a && a.cell.objectId !== own ? [a.cell] : []);
    if (cells.length === 0) {
      // No cell read here: a variable that holds one (set.add(c) after char c = s.charAt(r)).
      const value = code.slice(code.search(/\+=|-=|=|\(/) + 1);
      // A counter array is indexed by it instead (freq[c - 'a']++).
      const index = e.kind === 'ARRAY_WRITE' ? /\[([^\]]*)\]/.exec(code)?.[1].match(/[A-Za-z_$][\w$]*/g) ?? [] : [];
      cells = [...new Set([...plainVariables(value), ...index])].flatMap(n => held.get(`${e.frameId}|${n}`) ?? []).filter(x => x.objectId !== own);
    }
    if (cells.length !== 1) return;
    found!.push({ step: e.step, key: `${'binding' in holder ? holder.binding : holder.object}|${cells[0].objectId}`, holder,
      source: cells[0].objectId, index: cells[0].index, dir });
  });
  cache.set(playback, found);
  return found;
}

/** The windows at cursor k, by the array or string they lie over. */
export function windowsAt(playback: Playback, k: number, state: State): Map<string, SlidingWindow[]> {
  const groups = new Map<string, { holder: Holder; source: string; cells: Set<number>; gone: Set<number>; lefts: number; broken: boolean; last: Move | null }>();
  for (const m of moves(playback)) {
    if (m.step > k) break;
    const g = groups.get(m.key) ?? { holder: m.holder, source: m.source, cells: new Set<number>(), gone: new Set<number>(), lefts: 0, broken: false, last: null };
    groups.set(m.key, g);
    if (m.dir === 'in') { g.cells.add(m.index); g.gone.delete(m.index); }
    else if (g.cells.delete(m.index)) { g.lefts++; g.gone.add(m.index); }
    else if (!g.gone.has(m.index)) g.broken = true; // took out a cell that never entered: not a window
    g.last = m;
  }
  const out = new Map<string, SlidingWindow[]>();
  for (const g of groups.values()) {
    if (g.broken || g.lefts === 0 || g.cells.size === 0) continue;
    const lo = Math.min(...g.cells), hi = Math.max(...g.cells);
    if (hi - lo + 1 !== g.cells.size) continue; // not contiguous: not a window
    if (!state.objects.has(g.source)) continue;
    const now = g.last && g.last.step === k ? g.last : null;
    const w: SlidingWindow = { objectId: g.source, lo, hi, label: labelOf(g.holder, state),
      entered: now?.dir === 'in' ? now.index : null, left: now?.dir === 'out' ? now.index : null };
    out.set(g.source, [...(out.get(g.source) ?? []), w]);
  }
  return out;
}

function labelOf(holder: Holder, state: State): string {
  if ('binding' in holder) {
    const b = state.bindings.find(x => x.bindingId === holder.binding);
    if (!b) return 'window';
    return `${b.name} = ${show(b.value)}`;
  }
  const o = state.objects.get(holder.object);
  const name = namesOf(state, holder.object)[0] ?? 'window';
  if (o?.kind === 'collection') return `${name} (${o.elements.length})`;
  if (o?.kind === 'map') return `${name} (${o.entries.length} keys)`;
  return name;
}

function show(v: Value): string {
  return 'value' in v && (typeof v.value === 'string' || typeof v.value === 'number' || typeof v.value === 'boolean') ? String(v.value) : '…';
}
