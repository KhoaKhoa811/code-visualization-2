import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { flowsAt, plainVariables } from './flows.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const views = (p: Playback) => Array.from({ length: p.steps }, (_, i) => p.at(i + 1));
const code = (p: Playback, v: ReturnType<Playback['at']>) => p.trace.source.text.slice(v.event!.source.start, v.event!.source.end);

test('variables on the value side are sources; receivers, indexes, methods and literals are not (21)', () => {
  assert.deepEqual(plainVariables(' a[j + 1] + t * 2'), ['t']);
  assert.deepEqual(plainVariables(' cur.next'), []);
  assert.deepEqual(plainVariables(' Math.max(best, x) + "x"'), ['best', 'x']);
});

test('a swap through a temporary: cell to temp, cell to cell, temp to cell, and the swap itself (21)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const all = views(p);
  const swap = all.find(v => flowsAt(p, v).some(a => a.kind === 'swap'))!;
  assert.ok(swap, 'a swap is found');
  const arrows = flowsAt(p, swap);
  const s = arrows.find(a => a.kind === 'swap')!;
  assert.ok('cell' in s.from && 'cell' in s.to && Math.abs(s.from.cell.index - s.to.cell.index) === 1, 'adjacent cells exchanged');
  assert.ok(arrows.some(a => a.kind === 'value' && 'binding' in a.from && 'cell' in a.to), `${code(p, swap)}: the temporary flows into the cell`);
  // The step before: a[j] = a[j + 1] is a cell-to-cell arrow, not a swap yet.
  const before = all.slice(0, swap.cursor - 1).reverse().find(v => v.event?.kind === 'ARRAY_WRITE')!;
  assert.ok(flowsAt(p, before).every(a => a.kind === 'value'));
  assert.ok(flowsAt(p, before).some(a => 'cell' in a.from && 'cell' in a.to), code(p, before));
  // Declaring the temporary from a cell.
  const temp = all.slice(0, swap.cursor).reverse().find(v => v.event?.kind === 'DECLARE')!;
  assert.ok(flowsAt(p, temp).some(a => 'cell' in a.from && 'binding' in a.to), code(p, temp));
});

test('fields, pointers and collections: n.prev.next = n.next reads n.next only; u = queue.poll() comes from the queue (21)', () => {
  const lru = new Playback(fixture('lru'));
  const write = views(lru).find(v => v.event?.kind === 'FIELD_WRITE' && code(lru, v).startsWith('n.prev.next = n.next'))!;
  const arrows = flowsAt(lru, write);
  assert.equal(arrows.length, 1);
  assert.ok('field' in arrows[0].from && arrows[0].from.field.name === 'next', 'from n.next, not from the receiver n.prev');

  const mixed = new Playback(fixture('mixed-structures'));
  const poll = views(mixed).find(v => v.event?.kind === 'DECLARE' && code(mixed, v).includes('queue.poll()'))!;
  assert.ok(flowsAt(mixed, poll).some(a => 'box' in a.from && 'binding' in a.to));
  // A step that stores nothing has no arrows.
  const cond = views(mixed).find(v => v.event?.kind === 'EXPRESSION')!;
  assert.deepEqual(flowsAt(mixed, cond), []);
});
test('a call passes each argument into its parameter: factorial(n - 1) flows from n to the new n (21)', () => {
  const p = new Playback(fixture('methods'));
  const call = views(p).find(v => v.event?.kind === 'CALL' && code(p, v) === 'factorial(n - 1)')!;
  const arrows = flowsAt(p, call);
  assert.equal(arrows.length, 1);
  const e = call.event as Extract<typeof call.event, { kind: 'CALL' }>;
  assert.deepEqual(arrows[0].to, { binding: e.data.parameters[0].bindingId }, 'into the new frame’s n');
  assert.ok('binding' in arrows[0].from && arrows[0].from.binding !== e.data.parameters[0].bindingId, 'from the caller’s n');
  // swap(a, 0, 2): only the array variable is a plain variable; constants flow from nowhere.
  const swap = views(p).find(v => v.event?.kind === 'CALL' && code(p, v) === 'swap(a, 0, 2)')!;
  assert.equal(flowsAt(p, swap).length, 1);
});