// Part 11a: each step's recorded operation shown as motion. A plan comes only from that step's own
// event (the value it stored, read or computed), never from guesses about the program; the runner
// measures where things are on the page and animates a small chip with the Web Animations API.
// Nothing is animated whose place is not on the page (a scrolled-away cell, a removed variable).
import { formatValue } from '../trace/format.ts';
import type { State } from '../trace/playback.ts';
import type { ObservableEvent } from '../trace/types.ts';

/** Where a motion starts or ends: the highlighted code, a variable's value, or an array cell. */
export type Anchor = { code: true } | { binding: string } | { cell: { objectId: string; index: number } };

export type Motion =
  // A value travels from one place to another; `leaving` is the value it replaces, dropping away.
  | { kind: 'flight'; text: string; from: Anchor; to: Anchor; leaving?: string }
  // A computed result pops up at the code: 4 > 2 ✓, 3 + 4 = 7.
  | { kind: 'badge'; text: string; at: Anchor; verdict?: boolean };

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
    case 'ARRAY_WRITE':
      return flight(value(event.data.value, after), CODE, { cell: { objectId: event.data.objectId, index: event.data.index } },
        value(event.data.previous, before));
    case 'ARRAY_READ': {
      const read: Motion = { kind: 'flight', text: value(event.data.value, after), from: { cell: { objectId: event.data.objectId, index: event.data.index } }, to: CODE };
      return forward ? [read] : [];
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

function place(anchor: Anchor): DOMRect | null {
  let element: Element | null;
  if ('code' in anchor) element = document.querySelector('.step-highlight-text');
  else if ('binding' in anchor) element = document.querySelector(`[data-binding="${CSS.escape(anchor.binding)}"] .value`);
  else element = document.querySelector(`[data-object="${CSS.escape(anchor.cell.objectId)}"][data-index="${anchor.cell.index}"] .cell-value`);
  if (!element) return null;
  const rect = element.getBoundingClientRect();
  const visible = rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0
    && rect.top < window.innerHeight && rect.left < window.innerWidth;
  return visible ? rect : null;
}

function target(anchor: Anchor): Element | null {
  if ('binding' in anchor) return document.querySelector(`[data-binding="${CSS.escape(anchor.binding)}"] .value`);
  if ('cell' in anchor) return document.querySelector(`[data-object="${CSS.escape(anchor.cell.objectId)}"][data-index="${anchor.cell.index}"] .cell-value`);
  return null;
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
