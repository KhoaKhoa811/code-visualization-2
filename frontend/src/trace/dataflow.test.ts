import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { flowAt } from './dataflow.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

test('a write is drawn as computed from the reads inside its own code, in its frame (18a)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const text = p.trace.source.text;
  let checked = 0;
  for (let k = 1; k <= p.steps; k++) {
    const v = p.at(k);
    const flow = flowAt(p, v);
    if (!v.event || v.event.kind !== 'ARRAY_WRITE') { assert.equal(flow, null, `step ${k}: only writes have a flow`); continue; }
    const code = text.slice(v.event.source.start, v.event.source.end);
    // a[j] = a[j + 1] reads one cell; a[j + 1] = t reads none (t is a variable).
    if (/=\s*a\[/.test(code)) assert.equal(flow!.sources.length, 1, code);
    else assert.equal(flow!.sources.length, 0, code);
    for (const s of flow!.sources) assert.notDeepEqual(s, flow!.target, 'no arrow to itself');
    checked++;
  }
  assert.ok(checked > 0);
});

test('a grid write reads cells of other rows; fetching a row is not a source (18a)', () => {
  const p = new Playback(fixture('grid-rotate')); // r[j][n - 1 - i] = m[i][j]
  const k = Array.from({ length: p.steps }, (_, i) => i + 1).find(i => p.at(i).event?.kind === 'ARRAY_WRITE')!;
  const flow = flowAt(p, p.at(k))!;
  assert.equal(flow.sources.length, 1, 'only m[i][j], not the row m[i]');
  const row = p.at(k).state.objects.get(flow.sources[0].objectId);
  assert.equal(row?.kind, 'array');
});