// Part 21 (specs/flow-arrows.md): where the value a step stored came from, as arrows between the
// places that hold values: array cells, variables, object fields and collections. Sources are the
// recorded reads of the same frame inside the step's own statement (a[j], node.key, queue.poll()),
// and the variables written literally on the right of its assignment (t in a[j + 1] = t); index
// expressions and receivers are not sources. A swap is two writes that exchanged the values of two
// cells, read from the recorded values before and after. Nothing is inferred beyond those facts.
import type { Playback, View } from './playback.ts';
import type { Anchor } from '../views/motion.ts';
import type { ObservableEvent, TraceEvent, Value } from './types.ts';

export interface FlowArrow { from: Anchor; to: Anchor; kind: 'value' | 'swap' }

const same = (a: Value, b: Value) => JSON.stringify(a) === JSON.stringify(b);
const sameAnchor = (a: Anchor, b: Anchor) => JSON.stringify(a) === JSON.stringify(b);

/** Where the step's value side starts in its code: after its assignment operator, or inside the last call's parentheses. */
function rightStart(code: string): number {
  let depth = 0;
  for (let i = 0; i < code.length; i++) {
    const ch = code[i];
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === ')' || ch === ']' || ch === '}') depth--;
    else if (ch === '=' && depth === 0 && code[i + 1] !== '=' && !'=!<>'.includes(code[i - 1] ?? '')) return i + 1;
  }
  // A call (list.add(x), adj.get(u).add(v)): the arguments of the outermost last call.
  const close = code.lastIndexOf(')');
  if (close < 0) return code.length;
  for (let i = close - 1, d = 0; i >= 0; i--) {
    if (code[i] === ')') d++;
    else if (code[i] === '(') { if (d === 0) return i + 1; d--; }
  }
  return code.length;
}
/** Plain variables in code: not method names, receivers, array names or anything inside [ ]. */
export function plainVariables(code: string): string[] {
  const out: string[] = [];
  let depth = 0;
  const re = /[A-Za-z_$][\w$]*|\[|\]|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(code))) {
    const t = m[0];
    if (t === '[') { depth++; continue; }
    if (t === ']') { depth--; continue; }
    if (t.startsWith('"') || t.startsWith("'") || depth > 0) continue;
    const before = code.slice(0, m.index).trimEnd().slice(-1), after = code.slice(m.index + t.length).trimStart()[0];
    if (before === '.' || after === '(' || after === '[' || after === '.') continue;
    if (/^(new|true|false|null|this|int|long|double|char|boolean|return)$/.test(t)) continue;
    if (!out.includes(t)) out.push(t);
  }
  return out;
}

/** Recorded reads of the same frame inside the value side of the statement of events[at] (from offset from), as anchors. */
function readsOf(events: TraceEvent[], at: number, from: number, to = Infinity): Anchor[] {
  const step = events[at] as ObservableEvent;
  const out: Anchor[] = [];
  for (let i = at - 1; i >= 0; i--) {
    const e = events[i];
    if (!('step' in e)) continue;
    if (e.frameId !== step.frameId) continue; // inside a call the statement made
    if (e.source.start < step.source.start || e.source.end > step.source.end) break;
    if (e.source.start < from || e.source.end > to) continue; // the target's own receiver or index (n.prev in n.prev.next = n.next)
    if (e.kind === 'ARRAY_READ') out.unshift({ cell: { objectId: e.data.objectId, index: e.data.index } });
    if (e.kind === 'FIELD_READ') out.unshift({ field: { objectId: e.data.objectId, name: e.data.field } });
    // A value taken out of a string or collection: s.charAt(i) from that character, list.get(i) from that
    // item, anything else (poll, pop, peek) from the collection as a whole.
    if (e.kind === 'LIBRARY' && e.data.objectId && e.data.result && e.data.result.kind !== 'boolean') {
      const i = (e.data.arguments as Value[])[0];
      const index = i?.kind === 'int' ? Number(i.value) : null;
      if (index !== null && e.data.method === 'java.lang.String.charAt(int)') out.unshift({ cell: { objectId: e.data.objectId, index } });
      else if (index !== null && /\.get\(int\)$/.test(e.data.method)) out.unshift({ item: { objectId: e.data.objectId, index } });
      else out.unshift({ box: e.data.objectId });
    }
  }
  return out;
}

/** Where the step stored its value, or null for steps that store nothing. */
function targetOf(e: ObservableEvent): Anchor | null {
  switch (e.kind) {
    case 'ARRAY_WRITE': return { cell: { objectId: e.data.objectId, index: e.data.index } };
    case 'DECLARE': return { binding: e.data.binding.bindingId };
    case 'ASSIGN': case 'UPDATE': return { binding: e.data.bindingId };
    case 'FIELD_WRITE': return { field: { objectId: e.data.objectId, name: e.data.field } };
    case 'LIBRARY': return e.data.objectId && e.data.after ? { box: e.data.objectId } : null; // add, push, offer, put, set
    default: return null;
  }
}

/** Split a call's argument text at its top-level commas, with each argument's offset. */
function argumentsOf(code: string): { text: string; start: number }[] {
  const open = code.indexOf('(');
  if (open < 0) return [];
  const out: { text: string; start: number }[] = [];
  let depth = 0, from = open + 1;
  for (let i = open + 1; i < code.length; i++) {
    const ch = code[i];
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if ((ch === ')' || ch === ']' || ch === '}') && depth > 0) depth--;
    else if ((ch === ',' && depth === 0) || (ch === ')' && depth === 0)) {
      if (code.slice(from, i).trim()) out.push({ text: code.slice(from, i), start: from });
      from = i + 1;
      if (ch === ')') break;
    }
  }
  return out;
}

/**
 * A call: each argument flows into its parameter in the new frame. Sources of argument i are the
 * reads of the caller inside that argument and the variables written in it.
 */
function callFlows(playback: Playback, view: View, e: ObservableEvent & { kind: 'CALL' }): FlowArrow[] {
  const events = playback.trace.events;
  const state = view.beforeEnd ?? view.state;
  const code = playback.trace.source.text.slice(e.source.start, e.source.end);
  const args = argumentsOf(code);
  const params = e.data.parameters.slice(e.data.parameters.length - args.length); // 	his comes first for instance methods
  return args.flatMap((arg, i) => {
    const to: Anchor = { binding: params[i]?.bindingId ?? '' };
    if (!params[i]) return [];
    const start = e.source.start + arg.start, end = start + arg.text.length;
    const reads = readsOf(events, e.seq - 1, start, end); // reads inside this argument only
    const vars = plainVariables(arg.text).flatMap(name => {
      const b = state.bindings.find(x => x.frameId === e.frameId && x.name === name);
      return b ? [{ binding: b.bindingId } as Anchor] : [];
    });
    return [...reads, ...vars].map(from => ({ from, to, kind: 'value' as const }));
  });
}

/** The arrows of the step at the view's cursor. */
export function flowsAt(playback: Playback, view: View): FlowArrow[] {
  const e = view.event;
  if (!e) return [];
  if (e.kind === 'CALL') return callFlows(playback, view, e);
  const target = targetOf(e);
  if (!target) return [];
  const events = playback.trace.events;
  const at = e.seq - 1;
  const state = view.beforeEnd ?? view.state;
  const code = playback.trace.source.text.slice(e.source.start, e.source.end);
  const from = rightStart(code);
  const sources: Anchor[] = readsOf(events, at, e.source.start + from);
  // Variables written on the right side, visible in the step's frame (not the one it assigns).
  const assigned = 'binding' in target ? target.binding : null;
  for (const name of plainVariables(code.slice(from))) {
    const b = state.bindings.find(x => x.frameId === e.frameId && x.name === name);
    if (b && b.bindingId !== assigned) sources.push({ binding: b.bindingId });
  }
  const arrows: FlowArrow[] = sources.filter(s => !sameAnchor(s, target)).map(from => ({ from, to: target, kind: 'value' as const }));
  const swap = e.kind === 'ARRAY_WRITE' ? swapOf(events, at) : null;
  if (swap) arrows.push(swap);
  return arrows;
}

/**
 * A swap completed by the ARRAY_WRITE at events[at]: an earlier write to the same array, within the
 * 4 steps before, at another index, with no write to either cell between; the earlier write stored
 * this cell's old value and this write stores the other cell's old value (and the values differ).
 */
export function swapOf(events: TraceEvent[], at: number): FlowArrow | null {
  const w = events[at];
  if (w.kind !== 'ARRAY_WRITE') return null;
  let seen = 0;
  for (let i = at - 1; i >= 0 && seen < 4; i--) {
    const e = events[i];
    if (!('step' in e)) continue;
    seen++;
    if (e.kind !== 'ARRAY_WRITE' || e.data.objectId !== w.data.objectId) continue;
    if (e.data.index === w.data.index) return null; // this cell was written again in between
    const exchanged = same(e.data.value, w.data.previous) && same(w.data.value, e.data.previous) && !same(e.data.previous, w.data.previous);
    if (!exchanged) return null;
    return { from: { cell: { objectId: e.data.objectId, index: e.data.index } }, to: { cell: { objectId: w.data.objectId, index: w.data.index } }, kind: 'swap' };
  }
  return null;
}
