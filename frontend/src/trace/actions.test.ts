import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { MAX_ACTIONS, actionsAt, jumpAt, type Action } from './actions.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const views = (p: Playback) => Array.from({ length: p.steps }, (_, i) => p.at(i + 1));
const code = (p: Playback, v: ReturnType<Playback['at']>) => p.trace.source.text.slice(v.event!.source.start, v.event!.source.end);
const find = (p: Playback, kind: Action['kind']) => {
  for (const v of views(p)) { const a = actionsAt(p, v).find(x => x.kind === kind); if (a) return { v, a, all: actionsAt(p, v) }; }
  return null;
};

test('no step shows more than the budget of actions, plus at most one old-value ghost (22)', () => {
  for (const name of ['bubble-sort', 'lru', 'mixed-structures', 'objects', 'methods', 'chaining', 'dp-knapsack']) {
    const p = new Playback(fixture(name));
    for (const v of views(p)) {
      const all = actionsAt(p, v);
      assert.ok(all.filter(a => a.kind !== 'ghost').length <= MAX_ACTIONS, `${name} step ${v.cursor}`);
      assert.ok(all.filter(a => a.kind === 'ghost').length <= 1, `${name} step ${v.cursor}`);
    }
  }
});

test('comparisons, swaps and replaced values on an array (22)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const cmp = find(p, 'compare')!;
  assert.ok(cmp.a.kind === 'compare' && 'cell' in cmp.a.a && 'cell' in cmp.a.b && /^\d+ > \d+ [✓✗]$/.test(cmp.a.text), JSON.stringify(cmp.a));
  const swap = find(p, 'swap')!;
  assert.deepEqual(swap.all.map(a => a.kind).sort(), ['flow', 'ghost', 'swap'], 'the swap, t into the cell, and the value it replaced');
});

test('links rewired, a pointer moving, a returned value, push and pop, and a hash slot (22)', () => {
  const lru = new Playback(fixture('lru'));
  const re = find(lru, 'rewire')!;
  assert.ok(re.a.kind === 'rewire' && re.a.old !== re.a.now);
  assert.ok(!re.all.some(a => a.kind === 'flow' && a.arrows.some(f => 'field' in f.to)), 'the field arrow is not repeated as a value arrow');

  const objects = new Playback(fixture('objects'));
  const move = find(objects, 'move')!;
  assert.ok(move.a.kind === 'move' && move.a.from !== move.a.to, code(objects, move.v));

  const methods = new Playback(fixture('methods'));
  const ret = views(methods).find(v => v.event?.kind === 'DECLARE' && code(methods, v).startsWith('int f = factorial(3)'))!;
  const r = actionsAt(methods, ret).find(a => a.kind === 'return');
  assert.ok(r && r.kind === 'return' && r.text === 'returned 6');

  const mixed = new Playback(fixture('mixed-structures'));
  const stack = views(mixed).flatMap(v => actionsAt(mixed, v)).filter((a): a is Extract<Action, { kind: 'stack' }> => a.kind === 'stack');
  assert.ok(stack.some(a => a.dir === 'in' && a.text.startsWith('offer ')) && stack.some(a => a.dir === 'out' && a.text.startsWith('poll → ')));
  assert.ok(stack.some(a => a.text.startsWith('push ')) && stack.some(a => a.text.startsWith('pop → ')));

  const chaining = new Playback(fixture('chaining'));
  const slot = find(chaining, 'slot')!;
  assert.ok(slot.a.kind === 'slot' && /^\d+ % 4 = \d$/.test(slot.a.text), JSON.stringify(slot.a));
});

test('the code jumps back when a loop goes round and over a branch not taken (22)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const jumps = views(p).map(v => jumpAt(p, v)).filter(j => j !== null);
  assert.ok(jumps.some(j => j!.kind === 'back' && j!.to < j!.from));
  assert.equal(jumpAt(p, p.at(1)), null);
});