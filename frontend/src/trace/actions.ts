// Part 22 (specs/step-actions.md): what a step does, drawn on the picture. Every action comes from
// the step's own recorded event (and, for a code jump, the step just before it); nothing is guessed.
// To keep one picture readable, a step shows at most MAX_ACTIONS actions, chosen by priority, an
// action that says what a value arrow says replaces that arrow, and at most one old-value ghost.
import type { Playback, View } from './playback.ts';
import type { Anchor } from '../views/motion.ts';
import { flowsAt, plainVariables, readsOf, type FlowArrow } from './flows.ts';
import type { ObservableEvent, TraceEvent, Value } from './types.ts';

export type Action =
  | { kind: 'swap'; from: Anchor; to: Anchor }
  /** A link field changed: the old target (red, crossed) and the new one (green). */
  | { kind: 'rewire'; object: string; field: string; old: string | null; now: string | null }
  /** A reference variable moved from one object to another (cur = cur.next). */
  | { kind: 'move'; name: string; from: string; to: string }
  /** Two values compared: a bracket between them with the verdict. */
  | { kind: 'compare'; a: Anchor; b: Anchor; text: string; verdict: boolean }
  /** A value a call returned flows from its call-tree node to where the caller stored it. */
  | { kind: 'return'; call: string; to: Anchor; text: string }
  /** A value pushed or enqueued into a stack, deque or queue, or popped or dequeued out of it. */
  | { kind: 'stack'; box: string; dir: 'in' | 'out'; text: string }
  /** A hash slot: the key variable to the cell its `% n` index selects. */
  | { kind: 'slot'; from: Anchor; to: Anchor; text: string }
  /** The step's value arrows (Part 21), one action however many arrows. */
  | { kind: 'flow'; arrows: FlowArrow[] }
  /** The value a write replaced, crossed out beside the target for this step. */
  | { kind: 'ghost'; at: Anchor; text: string };

/** A jump between code lines: back to a loop's header, or over lines skipped (an if not taken). */
export interface Jump { from: number; to: number; kind: 'back' | 'skip' }

export const MAX_ACTIONS = 3;
const PRIORITY: Action['kind'][] = ['swap', 'rewire', 'move', 'compare', 'return', 'stack', 'slot', 'flow'];
const COMPARISONS = new Set(['<', '<=', '>', '>=', '==', '!=']);
const STACK_IN = new Set(['push', 'offer', 'add', 'addFirst', 'addLast', 'offerFirst', 'offerLast']);
const STACK_OUT = new Set(['pop', 'poll', 'remove', 'removeFirst', 'removeLast', 'pollFirst', 'pollLast']);

const show = (v: Value): string => {
  switch (v.kind) {
    case 'int': case 'long': case 'short': case 'byte': return v.value;
    case 'boolean': return String(v.value);
    case 'char': return `'${String.fromCharCode(v.value)}'`;
    case 'null': return 'null';
    case 'float': case 'double': {
      const view = new DataView(new ArrayBuffer(8));
      if (v.bits.length === 8) { view.setUint32(0, parseInt(v.bits, 16)); return String(view.getFloat32(0)); }
      view.setBigUint64(0, BigInt(`0x${v.bits}`)); return String(view.getFloat64(0));
    }
    default: return '…';
  }
};
const sameAnchor = (a: Anchor, b: Anchor) => JSON.stringify(a) === JSON.stringify(b);

/** Every action the step at the view's cursor shows, already chosen and limited. */
export function actionsAt(playback: Playback, view: View): Action[] {
  const e = view.event;
  if (!e) return [];
  const events = playback.trace.events;
  const at = e.seq - 1;
  const state = view.beforeEnd ?? view.state;
  const code = playback.trace.source.text.slice(e.source.start, e.source.end);
  const found: Action[] = [];

  const flows = flowsAt(playback, view);
  const swap = flows.find(f => f.kind === 'swap');
  if (swap) found.push({ kind: 'swap', from: swap.from, to: swap.to });
  let values = flows.filter(f => f.kind === 'value');

  if (e.kind === 'FIELD_WRITE' && (e.data.previous.kind === 'ref' || e.data.previous.kind === 'null') && (e.data.value.kind === 'ref' || e.data.value.kind === 'null')
      && isObject(state, e.data.value) && isObject(state, e.data.previous)) {
    const old = e.data.previous.kind === 'ref' ? e.data.previous.objectId : null, now = e.data.value.kind === 'ref' ? e.data.value.objectId : null;
    if (old !== now) {
      found.push({ kind: 'rewire', object: e.data.objectId, field: e.data.field, old, now });
      values = values.filter(f => !('field' in f.to && f.to.field.objectId === e.data.objectId && f.to.field.name === e.data.field));
    }
  }
  if (e.kind === 'ASSIGN' && e.data.previous.kind === 'ref' && e.data.value.kind === 'ref' && e.data.previous.objectId !== e.data.value.objectId
      && state.objects.get(e.data.previous.objectId)?.kind === 'object' && state.objects.get(e.data.value.objectId)?.kind === 'object') {
    const name = state.bindings.find(b => b.bindingId === e.data.bindingId)?.name ?? '?';
    found.push({ kind: 'move', name, from: e.data.previous.objectId, to: e.data.value.objectId });
    values = values.filter(f => !('binding' in f.to && f.to.binding === e.data.bindingId));
  }
  if (e.kind === 'EXPRESSION' && COMPARISONS.has(e.data.operator) && e.data.operands.length === 2 && e.data.result.kind === 'boolean') {
    const reads = readsOf(events, at, e.source.start).filter(a => 'cell' in a || 'field' in a || 'item' in a);
    const vars = plainVariables(code).flatMap(n => {
      const b = state.bindings.find(x => x.frameId === e.frameId && x.name === n);
      return b ? [{ binding: b.bindingId } as Anchor] : [];
    });
    // The two compared places: two cells (a[j] > a[j + 1]), or a cell and a variable (a[i] > max).
    const sides = reads.length >= 2 ? reads.slice(-2) : reads.length === 1 && vars.length >= 1 ? [reads[0], vars[0]] : [];
    if (sides.length === 2 && !sameAnchor(sides[0], sides[1])) {
      const [x, y] = e.data.operands.map(o => 'value' in o ? show(o.value) : '…');
      found.push({ kind: 'compare', a: sides[0], b: sides[1], text: `${x} ${e.data.operator} ${y} ${e.data.result.value ? '✓' : '✗'}`, verdict: e.data.result.value });
    }
  }
  const target = targetOf(e);
  if (target) {
    // The last call of the statement whose value was stored: from its call-tree node.
    const call = lastCall(events, at);
    if (call) found.push({ kind: 'return', call: call.frame, to: target, text: `returned ${show(call.result)}` });
  }
  if (e.kind === 'LIBRARY' && e.data.objectId && state.objects.get(e.data.objectId)?.kind === 'collection') {
    const name = e.data.method.slice(e.data.method.lastIndexOf('.', e.data.method.indexOf('(')) + 1, e.data.method.indexOf('('));
    const args = e.data.arguments as Value[];
    const dir = STACK_IN.has(name) && args.length === 1 ? 'in' : STACK_OUT.has(name) && args.length === 0 && e.data.result ? 'out' : null;
    if (dir) {
      found.push({ kind: 'stack', box: e.data.objectId, dir, text: dir === 'in' ? `${name} ${show(args[0])}` : `${name} → ${show(e.data.result as Value)}` });
      values = values.filter(f => !('box' in f.to && f.to.box === e.data.objectId));
    }
  }
  if (e.kind === 'ARRAY_READ' || e.kind === 'ARRAY_WRITE') {
    const index = /\[([^\]]*%[^\]]*)\]/.exec(code);
    const mod = index ? [...events.slice(0, at)].reverse().find(x => 'step' in x && x.frameId === e.frameId && x.kind === 'EXPRESSION'
      && x.data.operator === '%' && x.source.start >= e.source.start) : undefined;
    const key = index ? plainVariables(index[1].split('%')[0])[0] : undefined;
    const b = key ? state.bindings.find(x => x.frameId === e.frameId && x.name === key) : undefined;
    if (mod && mod.kind === 'EXPRESSION' && b) {
      const [x, y] = mod.data.operands.map(o => 'value' in o ? show(o.value) : '…');
      found.push({ kind: 'slot', from: { binding: b.bindingId }, to: { cell: { objectId: e.data.objectId, index: e.data.index } }, text: `${x} % ${y} = ${show(mod.data.result)}` });
    }
  }
  if (values.length) found.push({ kind: 'flow', arrows: values });

  // The budget: the highest-priority actions first, at most MAX_ACTIONS of them.
  const chosen = found.sort((a, b) => PRIORITY.indexOf(a.kind) - PRIORITY.indexOf(b.kind)).slice(0, MAX_ACTIONS);
  // At most one ghost: the value a write replaced (primitives only; references are rewiring or moves).
  const ghost = ghostOf(e);
  if (ghost && !chosen.some(a => a.kind === 'rewire' || a.kind === 'move')) chosen.push(ghost);
  return chosen;
}

function isObject(state: View['state'], v: Value): boolean {
  return v.kind === 'null' || v.kind === 'ref' && state.objects.get(v.objectId)?.kind === 'object';
}

function targetOf(e: ObservableEvent): Anchor | null {
  switch (e.kind) {
    case 'ARRAY_WRITE': return { cell: { objectId: e.data.objectId, index: e.data.index } };
    case 'DECLARE': return { binding: e.data.binding.bindingId };
    case 'ASSIGN': return { binding: e.data.bindingId };
    case 'FIELD_WRITE': return { field: { objectId: e.data.objectId, name: e.data.field } };
    default: return null;
  }
}

/** The last call made by the statement of events[at] in its frame that returned a value. */
function lastCall(events: TraceEvent[], at: number): { frame: string; result: Value } | null {
  const step = events[at] as ObservableEvent;
  for (let i = at - 1; i >= 0; i--) {
    const e = events[i];
    if (!('step' in e)) continue;
    if (e.frameId !== step.frameId) continue;
    if (e.source.start < step.source.start || e.source.end > step.source.end) break;
    if (e.kind === 'CALL') {
      const ret = events.slice(i + 1, at).find(r => r.kind === 'RETURN' && r.frameId === e.data.frame.frameId);
      if (ret?.kind === 'RETURN' && ret.data.result) return { frame: e.data.frame.frameId, result: ret.data.result };
    }
  }
  return null;
}

function ghostOf(e: ObservableEvent): Action | null {
  const old = e.kind === 'ARRAY_WRITE' || e.kind === 'FIELD_WRITE' ? e.data.previous : e.kind === 'ASSIGN' ? e.data.previous : null;
  const now = e.kind === 'ARRAY_WRITE' || e.kind === 'FIELD_WRITE' || e.kind === 'ASSIGN' ? e.data.value : null;
  if (!old || !now || old.kind === 'ref' || old.kind === 'null' || old.kind === 'unknown' || old.kind === 'unassigned' || JSON.stringify(old) === JSON.stringify(now)) return null;
  const at = targetOf(e);
  return at ? { kind: 'ghost', at, text: show(old) } : null;
}

/**
 * The code jump into this step from the step just before it, in the same frame: back to an earlier
 * line (a loop goes round again) or forward over lines with code (a branch not taken).
 */
export function jumpAt(playback: Playback, view: View): Jump | null {
  const e = view.event;
  if (!e || view.cursor < 2) return null;
  const events = playback.trace.events;
  let prev: ObservableEvent | undefined;
  for (let i = e.seq - 2; i >= 0 && !prev; i--) { const x = events[i]; if ('step' in x && x.step === e.step - 1) prev = x; }
  if (!prev || prev.frameId !== e.frameId) return null;
  const text = playback.trace.source.text;
  const lineOf = (offset: number) => text.slice(0, offset).split('\n').length;
  const from = lineOf(prev.source.start), to = lineOf(e.source.start);
  if (to < from) return { from, to, kind: 'back' };
  if (to > from + 1) {
    const between = text.split('\n').slice(from, to - 1);
    if (between.some(l => /[\w;]/.test(l.replace(/\/\/.*$/, '')) && !/^\s*}\s*(else\s*\{?)?\s*$/.test(l))) return { from, to, kind: 'skip' };
  }
  return null;
}
