// Part 20 (specs/dp-view.md): how a program stores values in a table and reuses them, from the
// recorded reads and writes only. A table is an array (one or two dimensions) or a map with
// integer keys that the program fills from its own earlier cells (bottom-up:
// dp[i][j] = dp[i - 1][j] + dp[i][j - 1]) or from the results of recursive calls while reading
// cells it stored before (top-down memoization: memo[n], memo.get(n)). At cursor k everything is
// read from steps 1..k: a cell is "computed" once a write stored a value into it, an initial fill
// (Arrays.fill(memo, -1)) is not a computation, and a read of a computed cell reuses it.
import type { Playback, State } from './playback.ts';
import { originOf, type Cell, type Origin } from './dataflow.ts';
import type { Value } from './types.ts';

export interface DpWrite extends Origin {
  step: number;
  cell: Cell;
  value: Value;
  /** The statement's code (dp[i][j] = dp[i - 1][j] + dp[i][j - 1]). */
  code: string;
  /** An initial fill (Arrays.fill), not a computed value. */
  initial: boolean;
  /** The method whose frame made the write (Main.fib(int):int). */
  methodId: string;
  /** The frame had called its own method before the write (a recursive computation). */
  recursive: boolean;
  frameId: string;
}

export interface DpRead { step: number; cell: Cell; value: Value; frameId: string }

export interface DpLog { writes: DpWrite[]; reads: DpRead[] }

const key = (c: Cell) => `${c.objectId}#${c.index}`;
const cache = new WeakMap<Playback, DpLog>();

const intOf = (v: Value | undefined): number | null =>
  v && (v.kind === 'int' || v.kind === 'long' || v.kind === 'short' || v.kind === 'byte') ? Number(v.value) : v?.kind === 'char' ? v.value : null;

/** Every cell write (with what it came from) and every value read of arrays and integer-keyed maps, for the whole run. */
export function dpLog(playback: Playback): DpLog {
  const known = cache.get(playback);
  if (known) return known;
  const events = playback.trace.events;
  const text = playback.trace.source.text;
  const log: DpLog = { writes: [], reads: [] };
  const methods = new Map<string, string>((playback.trace.initialState?.frames ?? []).map(f => [f.frameId, f.methodId]));
  // Per frame: the calls it made since its last write, with their results once they return.
  const made = new Map<string, Origin['calls']>();
  const pending = new Map<string, { caller: string; entry: Origin['calls'][number] }>();
  const recursive = new Set<string>(); // frames that called their own method
  events.forEach((e, at) => {
    if (e.kind === 'CALL') {
      const frame = e.data.frame.frameId, caller = e.data.frame.callerFrameId;
      methods.set(frame, e.data.frame.methodId);
      if (caller) {
        const entry = { methodId: e.data.frame.methodId, args: e.data.parameters.map(p => p.value), result: null as Value | null };
        made.set(caller, [...(made.get(caller) ?? []), entry]);
        pending.set(frame, { caller, entry });
        if (methods.get(caller) === e.data.frame.methodId) recursive.add(caller);
      }
    }
    if (e.kind === 'RETURN') { const p = pending.get(e.frameId); if (p) p.entry.result = e.data.result; }
    if (!('step' in e)) return;
    const methodId = methods.get(e.frameId) ?? '';
    const write = (cell: Cell, value: Value, origin: Origin) => {
      // A statement without calls of its own shows the recursive calls its frame made since the last write.
      const calls = origin.calls.length ? origin.calls : (made.get(e.frameId) ?? []).filter(c => c.methodId === methodId);
      log.writes.push({ step: e.step, cell, value, code: text.slice(e.source.start, e.source.end), initial: false, methodId,
        recursive: recursive.has(e.frameId), frameId: e.frameId, sources: origin.sources, calls });
      made.delete(e.frameId);
    };
    if (e.kind === 'ARRAY_WRITE') write({ objectId: e.data.objectId, index: e.data.index }, e.data.value, originOf(events, at));
    else if (e.kind === 'ARRAY_READ' && e.data.value.kind !== 'ref') {
      log.reads.push({ step: e.step, cell: { objectId: e.data.objectId, index: e.data.index }, value: e.data.value, frameId: e.frameId });
    } else if (e.kind === 'LIBRARY' && e.data.objectId === null && e.data.after?.kind === 'array' && e.data.method.startsWith('java.util.Arrays.fill(')) {
      // An initial fill stores a sentinel or a start value in every cell it covers: not computed.
      const after = e.data.after;
      after.elements.forEach((value, index) => log.writes.push({ step: e.step, cell: { objectId: after.objectId, index }, value,
        code: text.slice(e.source.start, e.source.end), initial: true, methodId, recursive: false, frameId: e.frameId, sources: [], calls: [] }));
    } else if (e.kind === 'LIBRARY' && e.data.objectId) {
      // A map with integer keys: put(k, v) stores, get(k) returning a value reads.
      const args = e.data.arguments as Value[];
      const k = intOf(args[0]);
      if (k === null) return;
      if (e.data.method.endsWith('.put(java.lang.Object,java.lang.Object)')) write({ objectId: e.data.objectId, index: k }, args[1], { sources: [], calls: [] });
      else if (e.data.method.endsWith('.get(java.lang.Object)') && e.data.result && e.data.result.kind !== 'null') {
        log.reads.push({ step: e.step, cell: { objectId: e.data.objectId, index: k }, value: e.data.result as Value, frameId: e.frameId });
      }
    }
  });
  cache.set(playback, log);
  return log;
}

export interface DpCell {
  /** The last write up to the cursor that computed this cell, or null. */
  computed: DpWrite | null;
  /** 1 for the first cell computed, 2 for the next, ... (by the step of its first computation). */
  order: number | null;
  /** Reads of the cell after it was computed (reuses of the stored value), up to the cursor. */
  reuses: number;
  /** Cells whose computation read this one, up to the cursor. */
  usedBy: Cell[];
  /** The cell holds only its initial fill or its default value. */
  initial: boolean;
}

export interface DpTable {
  /** The top-level array or map, and every array (or the map) holding its cells. */
  holderId: string;
  cellHolders: string[];
  kind: 'array' | 'grid' | 'map';
  cells: Map<string, DpCell>;
  /** How it was recognised: bottom-up (cells from cells) or top-down (recursive calls, then reuse). */
  style: 'bottom-up' | 'top-down';
  computed: number;
  reuses: number;
}

/** The holders of a table's cells: an array itself, a grid's rows, or a map. */
function holdersOf(state: State, id: string): { holders: string[]; kind: DpTable['kind'] } | null {
  const a = state.objects.get(id);
  if (a?.kind === 'map') return { holders: [id], kind: 'map' };
  if (a?.kind !== 'array') return null;
  if (!a.elementType.endsWith('[]')) return { holders: [id], kind: 'array' };
  const rows = a.elements.flatMap(e => {
    const r = e.kind === 'ref' ? state.objects.get(e.objectId) : undefined;
    return r?.kind === 'array' && !r.elementType.endsWith('[]') ? [r.objectId] : [];
  });
  return rows.length ? { holders: rows, kind: 'grid' } : null;
}

const blank = (): DpCell => ({ computed: null, order: null, reuses: 0, usedBy: [], initial: false });

/**
 * The DP tables at cursor k among the given arrays and maps. Bottom-up: at least 2 writes compute
 * a cell from other cells of the same table, reading 2 or more of them or storing a value unlike
 * the one cell read (a sort or a shift copies a cell's value unchanged: not a computation).
 * Top-down: at least 2 writes made in a recursive call of the writing method, and some computed
 * cell read again later (a reuse).
 */
export function dpTables(playback: Playback, k: number, state: State, ids: string[]): Map<string, DpTable> {
  const log = dpLog(playback);
  const out = new Map<string, DpTable>();
  for (const id of ids) {
    const found = holdersOf(state, id);
    if (!found) continue;
    const inTable = new Set(found.holders);
    const writes = log.writes.filter(w => w.step <= k && inTable.has(w.cell.objectId));
    const computedWrites = writes.filter(w => !w.initial);
    const same = (a: Value, b: Value) => JSON.stringify(a) === JSON.stringify(b);
    const computes = computedWrites.filter(w => {
      const own = w.sources.filter(s => inTable.has(s.objectId));
      return own.length >= 2 || own.length === 1 && !same(own[0].value, w.value);
    });
    const recursiveWrites = computedWrites.filter(w => w.recursive);

    const cells = new Map<string, DpCell>();
    const firstComputed = new Map<string, number>();
    for (const w of writes) {
      const c = cells.get(key(w.cell)) ?? blank();
      if (w.initial) { if (!c.computed) c.initial = true; }
      else { c.computed = w; c.initial = false; if (!firstComputed.has(key(w.cell))) firstComputed.set(key(w.cell), w.step); }
      cells.set(key(w.cell), c);
      if (!w.initial) for (const s of w.sources) {
        if (!inTable.has(s.objectId)) continue;
        const src = cells.get(key(s)) ?? blank();
        if (!src.usedBy.some(u => key(u) === key(w.cell))) src.usedBy.push(w.cell);
        cells.set(key(s), src);
      }
    }
    // Reads of a cell by one call with no table write between them are one reuse:
    // `if (memo[n] != -1) return memo[n];` reads it twice.
    let reuses = 0;
    const lastWrite = new Map<string, number>();
    for (const w of computedWrites) lastWrite.set(key(w.cell), Math.max(lastWrite.get(key(w.cell)) ?? 0, w.step));
    const counted = new Set<string>();
    for (const r of log.reads) {
      if (r.step > k || !inTable.has(r.cell.objectId)) continue;
      const first = firstComputed.get(key(r.cell));
      if (first === undefined || first >= r.step) continue;
      const writesBefore = computedWrites.filter(w => w.frameId === r.frameId && w.step < r.step).length;
      const once = `${key(r.cell)}|${r.frameId}|${writesBefore}`;
      if (counted.has(once)) continue;
      counted.add(once);
      cells.get(key(r.cell))!.reuses++;
      reuses++;
    }
    const bottomUp = computes.length >= 2;
    const topDown = !bottomUp && recursiveWrites.length >= 2 && reuses >= 1;
    if (!bottomUp && !topDown) continue;
    [...firstComputed.entries()].sort((a, b) => a[1] - b[1]).forEach(([cid], i) => { cells.get(cid)!.order = i + 1; });
    out.set(id, { holderId: id, cellHolders: found.holders, kind: found.kind, cells, style: bottomUp ? 'bottom-up' : 'top-down',
      computed: firstComputed.size, reuses });
  }
  return out;
}

export const cellKey = key;
