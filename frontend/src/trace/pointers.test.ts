import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { latestRows, pointersAt } from './pointers.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

test('a variable points into an array only after the code indexed it with that variable (11b)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const text = p.trace.source.text;
  const at = (k: number) => { const v = p.at(k); return pointersAt(p, k, v.beforeEnd ?? v.state); };
  // The first recorded access written as name[variable].
  const first = p.trace.events.find(e => (e.kind === 'ARRAY_READ' || e.kind === 'ARRAY_WRITE') && 'step' in e
    && /\[\s*[A-Za-z_]\w*\s*\]$/.test(text.slice(e.source.start, e.source.end)))!;
  assert.ok(first && 'step' in first && (first.kind === 'ARRAY_READ' || first.kind === 'ARRAY_WRITE'));
  const name = /\[\s*([A-Za-z_]\w*)\s*\]$/.exec(text.slice(first.source.start, first.source.end))![1];

  assert.equal(at(first.step - 1).size, 0, 'no arrow before the program indexed with the variable');
  const arrows = at(first.step).get(first.data.objectId)!;
  const arrow = arrows.find(a => a.name === name)!;
  assert.equal(arrow.value, first.data.index, 'the arrow sits at the index the access used');

  // Later the arrow follows the variable's own value; it never reads future steps.
  for (let k = first.step; k <= p.steps; k++) {
    const v = p.at(k);
    const state = v.beforeEnd ?? v.state;
    for (const a of at(k).get(first.data.objectId) ?? []) {
      const binding = state.bindings.find(b => b.frameId === a.frameId && b.name === a.name)!;
      assert.equal(String(a.value), String((binding.value as { value: string | number }).value), `step ${k}`);
    }
  }
  // A program that indexes only with expressions (a[j + 1]) or constants gives no arrow for them.
  assert.ok([...at(p.steps).values()].flat().every(a => /^[A-Za-z_]\w*$/.test(a.name)));
});

test('in a grid, the row variable points at rows and the column variable at the row it indexed last (12d)', () => {
  const p = new Playback(fixture('grid-rotate')); // r[j][n - 1 - i] = m[i][j]
  let checked = 0;
  for (let k = 1; k <= p.steps; k++) {
    const v = p.at(k);
    const state = v.beforeEnd ?? v.state;
    const all = pointersAt(p, k, state);
    const m = state.bindings.find(b => b.name === 'm');
    const i = state.bindings.find(b => b.name === 'i');
    if (!m || m.value.kind !== 'ref' || !i || i.value.kind !== 'int') continue;
    const grid = state.objects.get(m.value.objectId);
    if (grid?.kind !== 'array') continue;
    const rows = latestRows(grid, all);
    const shown = [...rows.entries()].filter(([, ps]) => ps.some(q => q.name === 'j'));
    assert.ok(shown.length <= 1, `step ${k}: one row shows j`);
    if (shown.length === 0) continue;
    // The row showing j is the one the program last read as m[i][j], so it is row i while i holds.
    const row = grid.elements.findIndex(e => e.kind === 'ref' && e.objectId === shown[0][0]);
    const iArrow = all.get(grid.objectId)?.find(q => q.name === 'i');
    assert.ok(iArrow, `step ${k}: m was indexed with i`);
    if (v.event?.kind === 'ARRAY_READ' && v.event.data.objectId === shown[0][0]) { assert.equal(row, iArrow.value, `step ${k}`); checked++; }
  }
  assert.ok(checked > 0, 'some m[i][j] reads were checked');
});