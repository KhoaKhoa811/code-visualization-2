// Part 11a: each step's recorded operation shown as motion. A plan comes only from that step's own
// event (the value it stored, read or computed), never from guesses about the program; the runner
// measures where things are on the page and animates a small chip with the Web Animations API.
// Nothing is animated whose place is not on the page (a scrolled-away cell, a removed variable).
import { formatValue } from '../trace/format.ts';
import type { State } from '../trace/playback.ts';
import type { ObservableEvent } from '../trace/types.ts';

/** Where a motion starts or ends: the highlighted code, a variable's value, or an array cell. */
export type Anchor = { code: true } | { binding: string } | { cell: { objectId: string; index: number } }
  | { frame: string } // 11c: a frame card of the call stack
  // 11d: an item of a collection, map or builder (its index as recorded), a whole box (array,
  // collection or object), a field of an object, a tree node, a node of a heap view.
  | { item: { objectId: string; index: number } } | { box: string } | { field: { objectId: string; name: string } }
  | { treeNode: string } | { heapNode: { objectId: string; index: number } };

export type Motion =
  // A value travels from one place to another; `leaving` is the value it replaces, dropping away.
  | { kind: 'flight'; text: string; from: Anchor; to: Anchor; leaving?: string }
  // A computed result pops up at the code: 4 > 2 ✓, 3 + 4 = 7.
  | { kind: 'badge'; text: string; at: Anchor; verdict?: boolean }
  // 11c: a new frame card slides onto the call stack.
  | { kind: 'enter'; frame: string }
  // 11d: something new pops into place; something changed in place pulses.
  | { kind: 'appear'; at: Anchor } | { kind: 'pulse'; at: Anchor };

const CODE: Anchor = { code: true };
const COMPARISONS = new Set(['<', '<=', '>', '>=', '==', '!=']);
const MAX_TEXT = 40;

const short = (text: string) => text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT - 1)}…` : text;

/**
 * The motions of one step. Forward, a value flies from the code into the variable or cell it is
 * stored in (from the cell to the code for a read). Backward, the same flight runs the other way,
 * undoing the step; badges are not repeated backward.
 */
export function motionFor(event: ObservableEvent | null, before: State, after: State, forward: boolean): Motion[] {
  if (!event) return [];
  const value = (v: Parameters<typeof formatValue>[0], state: State) => short(formatValue(v, state.objects));
  const flight = (text: string, from: Anchor, to: Anchor, leaving?: string): Motion[] =>
    forward ? [{ kind: 'flight', text, from, to, ...(leaving !== undefined ? { leaving } : {}) }]
      : [{ kind: 'flight', text, from: to, to: from }];
  switch (event.kind) {
    case 'DECLARE': case 'CATCH':
      return flight(value(event.data.binding.value, after), CODE, { binding: event.data.binding.bindingId });
    case 'ASSIGN': case 'UPDATE':
      return flight(value(event.data.value, after), CODE, { binding: event.data.bindingId }, value(event.data.previous, before));
    case 'ARRAY_WRITE': // 11d: the same value also lands on the node of a heap view of this array
      return [...flight(value(event.data.value, after), CODE, { cell: { objectId: event.data.objectId, index: event.data.index } },
        value(event.data.previous, before)),
        ...flight(value(event.data.value, after), CODE, { heapNode: { objectId: event.data.objectId, index: event.data.index } })];
    case 'ALLOCATE': { // 11d: a new array, collection or object pops into place
      const id = event.data.object.objectId;
      return forward ? [{ kind: 'appear', at: { box: id } }, { kind: 'appear', at: { treeNode: id } }] : [];
    }
    case 'FIELD_WRITE': // 11d: the value flies into the field; a tree node showing the object pulses
      return [...flight(value(event.data.value, after), CODE, { field: { objectId: event.data.objectId, name: event.data.field } },
        value(event.data.previous, before)), { kind: 'pulse', at: { treeNode: event.data.objectId } }];
    case 'LIBRARY':
      return libraryMotion(event.data.objectId, event.data.after !== null, before, after, forward);
    case 'ARRAY_READ': {
      const read: Motion = { kind: 'flight', text: value(event.data.value, after), from: { cell: { objectId: event.data.objectId, index: event.data.index } }, to: CODE };
      return forward ? [read] : [];
    }
    case 'CALL': { // 11c: the frame slides in, each argument flies into its parameter
      const params = event.data.parameters.flatMap(p => flight(value(p.value, after), CODE, { binding: p.bindingId }));
      return forward ? [{ kind: 'enter', frame: event.data.frame.frameId }, ...params] : params;
    }
    case 'RETURN': { // 11c: the result travels back to the caller's frame
      const caller = after.frames.at(-1); // `after` is the state once the frame returned, both ways
      if (!event.data.result || !caller) return [];
      return flight(value(event.data.result, after), CODE, { frame: caller.frameId });
    }
    case 'EXPRESSION': {
      if (!forward) return [];
      const op = event.data.operator;
      const operands = event.data.operands.map(o => 'skipped' in o ? '…' : value(o.value, after));
      const result = event.data.result;
      if (COMPARISONS.has(op) && operands.length === 2 && result.kind === 'boolean')
        return [{ kind: 'badge', text: `${operands[0]} ${op} ${operands[1]}`, at: CODE, verdict: result.value }];
      return [{ kind: 'badge', text: short(`${operands.join(` ${op} `)} = ${value(result, after)}`), at: CODE }];
    }
    default:
      return [];
  }
}

/** The shown items of a library object, as text, in the order its panel draws them by index. */
function items(state: State, id: string): string[] | null {
  const o = state.objects.get(id);
  if (!o) return null;
  if (o.kind === 'collection') return o.elements.map(v => formatValue(v, state.objects));
  if (o.kind === 'map') return o.entries.map(e => `${formatValue(e.key, state.objects)}=${formatValue(e.value, state.objects)}`);
  if (o.kind === 'builder') return o.utf16.map(u => String.fromCharCode(u));
  return null;
}

/**
 * 11d: a library call that changed its object (its `after`), compared item by item with the object
 * before the call: one item more pops in where the contents first differ and the value flies in
 * from the code; one item fewer flies out of the box to the code; other changes pulse.
 */
function libraryMotion(id: string | null, changed: boolean, before: State, after: State, forward: boolean): Motion[] {
  if (!id || !changed) return [];
  const was = items(before, id), now = items(after, id);
  if (!was || !now) return [];
  const first = (a: string[], b: string[]) => { let i = 0; while (i < a.length && i < b.length && a[i] === b[i]) i++; return i; };
  const trim = (s: string) => short(s);
  if (now.length === was.length + 1) {
    const i = first(was, now), item: Anchor = { item: { objectId: id, index: i } };
    return forward ? [{ kind: 'appear', at: item }, { kind: 'flight', text: trim(now[i]), from: CODE, to: item }]
      : [{ kind: 'flight', text: trim(now[i]), from: { box: id }, to: CODE }];
  }
  if (now.length === was.length - 1) {
    const i = first(now, was);
    return forward ? [{ kind: 'flight', text: trim(was[i]), from: { box: id }, to: CODE }]
      : [{ kind: 'appear', at: { item: { objectId: id, index: i } } }];
  }
  if (now.length !== was.length) return [{ kind: 'pulse', at: { box: id } }];
  const changes = now.flatMap((text, i) => text === was[i] ? [] : [i]);
  return changes.length > 6 ? [{ kind: 'pulse', at: { box: id } }]
    : changes.map(i => ({ kind: 'pulse' as const, at: { item: { objectId: id, index: i } } }));
}

// ---- running motions on the page ---------------------------------------------------------------

function layer(): HTMLElement {
  let element = document.getElementById('motion-layer');
  if (!element) {
    element = document.createElement('div');
    element.id = 'motion-layer';
    element.setAttribute('aria-hidden', 'true');
    document.body.appendChild(element);
  }
  return element;
}

/** The page element an anchor names, or null when it is not drawn. */
function elementOf(anchor: Anchor): Element | null {
  const q = (s: string) => document.querySelector(s);
  const e = (s: string) => CSS.escape(s);
  if ('code' in anchor) return q('.step-highlight-text');
  if ('binding' in anchor) return q(`[data-binding="${e(anchor.binding)}"] .value`);
  if ('frame' in anchor) return q(`[data-frame="${e(anchor.frame)}"] .frame-name`);
  if ('cell' in anchor) return q(`[data-object="${e(anchor.cell.objectId)}"][data-index="${anchor.cell.index}"] .cell-value`);
  if ('item' in anchor) {
    const row = q(`[data-collection="${e(anchor.item.objectId)}"] [data-item="${anchor.item.index}"]`);
    return row?.querySelector('.cell-value') ?? row;
  }
  if ('box' in anchor) return q(`[data-box="${e(anchor.box)}"]`);
  if ('field' in anchor) return q(`.object[data-object="${e(anchor.field.objectId)}"] [data-testid="field-${e(anchor.field.name)}"]`);
  if ('treeNode' in anchor) return q(`[data-tree-node="${e(anchor.treeNode)}"]`);
  return q(`[data-heap="${e(anchor.heapNode.objectId)}"] [data-heap-index="${anchor.heapNode.index}"]`);
}

function place(anchor: Anchor): DOMRect | null {
  const element = elementOf(anchor);
  if (!element) return null;
  const rect = element.getBoundingClientRect();
  const visible = rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0
    && rect.top < window.innerHeight && rect.left < window.innerWidth;
  return visible ? rect : null;
}

/** Where a landing chip makes the value fade in: the value-showing places, not whole boxes or code. */
function target(anchor: Anchor): Element | null {
  return 'binding' in anchor || 'cell' in anchor || 'item' in anchor || 'field' in anchor || 'heapNode' in anchor ? elementOf(anchor) : null;
}

function chip(text: string, className: string, x: number, y: number): HTMLElement {
  const element = document.createElement('div');
  element.className = className;
  element.textContent = text;
  element.style.left = `${x}px`;
  element.style.top = `${y}px`;
  layer().appendChild(element);
  return element;
}

const center = (r: DOMRect) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });

// Animations of page elements (a value fading in where a chip lands), cancelled with the chips.
const running: Animation[] = [];

/** Stops and removes every running motion (a new step, a jump, or motion switched off). */
export function clearMotion(): void {
  while (running.length) running.pop()!.cancel(); // cancelled fades leave the value fully shown
  const element = document.getElementById('motion-layer');
  if (element) element.replaceChildren();
}

/** Plays the motions of one step over {@code duration} ms. */
export function playMotion(motions: Motion[], duration: number): void {
  clearMotion();
  for (const m of motions) {
    if (m.kind === 'enter') {
      const card = document.querySelector(`[data-frame="${CSS.escape(m.frame)}"]`);
      const slide = card?.animate([
        { opacity: 0, transform: 'translateY(-14px) scale(0.97)' },
        { opacity: 1, transform: 'translateY(0) scale(1)' },
      ], { duration, easing: 'ease-out' });
      if (slide) running.push(slide);
      continue;
    }
    if (m.kind === 'appear' || m.kind === 'pulse') { // 11d
      const element = elementOf(m.at);
      const keyframes = m.kind === 'appear'
        ? [{ opacity: 0, transform: 'translateY(-12px) scale(0.6)' }, { opacity: 1, transform: 'translateY(0) scale(1.08)', offset: 0.7 }, { opacity: 1, transform: 'scale(1)' }]
        : [{ transform: 'scale(1)' }, { transform: 'scale(1.12)', offset: 0.4 }, { transform: 'scale(1)' }];
      const animation = element?.animate(keyframes, { duration, easing: 'ease-out' });
      if (animation) running.push(animation);
      continue;
    }
    if (m.kind === 'badge') {
      const at = place(m.at);
      if (!at) continue;
      const badge = chip(m.verdict === undefined ? m.text : `${m.text} ${m.verdict ? '✓' : '✗'}`,
        `motion-badge${m.verdict === undefined ? '' : m.verdict ? ' yes' : ' no'}`, at.left, at.top - 30);
      badge.animate([
        { opacity: 0, transform: 'translateY(8px) scale(0.7)' },
        { opacity: 1, transform: 'translateY(0) scale(1)', offset: 0.2 },
        { opacity: 1, transform: 'translateY(0) scale(1)', offset: 0.8 },
        { opacity: 0, transform: 'translateY(-6px) scale(1)' },
      ], { duration: duration * 2, easing: 'ease-out' }).finished.then(() => badge.remove(), () => badge.remove());
      continue;
    }
    const from = place(m.from), to = place(m.to);
    if (!from || !to) continue;
    const a = center(from), b = center(to);
    const dx = b.x - a.x, dy = b.y - a.y;
    const lift = Math.min(60, 20 + Math.hypot(dx, dy) / 6); // an arc, so the value is seen travelling
    const flying = chip(m.text, 'motion-chip', a.x, a.y);
    flying.animate([
      { transform: 'translate(-50%, -50%) scale(1.15)', opacity: 0.4 },
      { transform: `translate(calc(-50% + ${dx / 2}px), calc(-50% + ${dy / 2 - lift}px)) scale(1.15)`, opacity: 1, offset: 0.5 },
      { transform: `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px)) scale(1)`, opacity: 1 },
    ], { duration, easing: 'ease-in-out' }).finished.then(() => flying.remove(), () => flying.remove());
    // The destination shows its new value as the chip lands.
    const landing = target(m.to)?.animate([{ opacity: 0 }, { opacity: 0, offset: 0.75 }, { opacity: 1 }], { duration });
    if (landing) running.push(landing);
    if (m.leaving !== undefined && m.leaving !== m.text) {
      const old = chip(m.leaving, 'motion-chip leaving', b.x, b.y);
      old.animate([
        { transform: 'translate(-50%, -50%)', opacity: 1 },
        { transform: 'translate(-50%, calc(-50% + 26px)) rotate(-8deg)', opacity: 0 },
      ], { duration: duration * 0.8, easing: 'ease-in' }).finished.then(() => old.remove(), () => old.remove());
    }
  }
}
