import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback, type State } from './playback.ts';
import { cellKey, dpLog, dpTables } from './dp.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const at = (p: Playback, k: number) => { const v = p.at(k); const s = v.beforeEnd ?? v.state; return { s, ids: [...new Set(s.bindings.flatMap(b => b.value.kind === 'ref' ? [b.value.objectId] : []))] }; };
const named = (s: State, name: string) => { const b = s.bindings.find(x => x.name === name)!; return (b.value as { objectId: string }).objectId; };

test('tables filled from their own cells or by recursive calls are DP tables; sorts and copies are not (20a, 20b)', () => {
  const kinds = (name: string) => { const p = new Playback(fixture(name)); const { s, ids } = at(p, p.steps);
    return [...dpTables(p, p.steps, s, ids).values()].map(t => `${s.bindings.find(b => b.value.kind === 'ref' && b.value.objectId === t.holderId)!.name}: ${t.kind} ${t.style}`).sort(); };
  assert.deepEqual(kinds('dp-grid'), ['paths: grid bottom-up']);
  assert.deepEqual(kinds('dp-knapsack'), ['best: grid bottom-up']);
  assert.deepEqual(kinds('dp-memo'), ['cache: map top-down', 'memo: array top-down']);
  for (const name of ['bubble-sort', 'grid-rotate', 'nested-arrays', 'heap-class']) assert.deepEqual(kinds(name), [], name);
});

test('every cell knows when it was computed, from which cells, how often it was reused and what used it (20a)', () => {
  const p = new Playback(fixture('dp-grid')); // paths[r][c] = paths[r - 1][c] + paths[r][c - 1], 3 x 4
  const { s, ids } = at(p, p.steps);
  const t = dpTables(p, p.steps, s, ids).get(named(s, 'paths'))!;
  const grid = s.objects.get(t.holderId)!;
  assert.ok(grid.kind === 'array');
  const row = (r: number) => (grid.elements[r] as { objectId: string }).objectId;
  const cell = (r: number, c: number) => t.cells.get(cellKey({ objectId: row(r), index: c }))!;
  assert.equal(t.computed, 12);
  assert.equal(cell(0, 0).order, 1);
  const last = cell(2, 3);
  assert.deepEqual(last.computed!.sources.map(x => [x.objectId, x.index, (x.value as { value: string }).value]),
    [[row(1), 3, '4'], [row(2), 2, '6']], 'paths[2][3] = paths[1][3] + paths[2][2] = 4 + 6');
  assert.equal(cell(1, 1).reuses, 2, 'paths[1][1] is read for paths[1][2] and paths[2][1]');
  assert.deepEqual(cell(1, 1).usedBy.map(u => [u.objectId, u.index]), [[row(1), 2], [row(2), 1]]);
  // No future facts: just before paths[2][3] is written it is not computed, and nothing used it.
  const write = dpLog(p).writes.find(w => w.cell.objectId === row(2) && w.cell.index === 3)!;
  const before = at(p, write.step - 1);
  const t0 = dpTables(p, write.step - 1, before.s, before.ids).get(t.holderId)!;
  assert.equal(t0.cells.get(cellKey({ objectId: row(2), index: 3 }))?.computed ?? null, null);
  assert.equal(t0.computed, 11);
});

test('top-down: initial fills are not computed values; stores come from recursive calls; reads of stored cells are reuses (20b)', () => {
  const p = new Playback(fixture('dp-memo'));
  const { s, ids } = at(p, p.steps);
  const tables = dpTables(p, p.steps, s, ids);
  const memo = tables.get(named(s, 'memo'))!;
  const id = named(s, 'memo');
  assert.equal(memo.cells.get(cellKey({ objectId: id, index: 0 }))!.initial, true, 'memo[0] holds only the -1 fill');
  const two = memo.cells.get(cellKey({ objectId: id, index: 2 }))!;
  assert.deepEqual(two.computed!.calls.map(c => (c.result as { value: string }).value), ['1', '0'], 'memo[2] = fib(1) + fib(0)');
  assert.ok(memo.reuses >= 1 && [...memo.cells.values()].some(c => c.reuses >= 1), 'a stored value is reused');
  const cache = tables.get(named(s, 'cache'))!;
  assert.equal(cache.computed, 5, 'keys 2..6 stored with put');
  assert.equal(cache.reuses, memo.reuses, 'the map memo is reused as often as the array memo');
});