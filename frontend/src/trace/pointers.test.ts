import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { pointersAt } from './pointers.ts';
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
