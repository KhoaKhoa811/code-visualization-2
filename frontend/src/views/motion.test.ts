import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from '../trace/playback.ts';
import type { Trace } from '../trace/types.ts';
import { motionFor } from './motion.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`../trace/fixtures/${name}.json`, import.meta.url), 'utf8'));

test('each step plans motion from its own event only, and backward runs it the other way (11a)', () => {
  const p = new Playback(fixture('bubble-sort'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const state = (k: number) => views[k].beforeEnd ?? views[k].state;
  const find = (kind: string) => views.findIndex(v => v.event?.kind === kind);

  const write = find('ARRAY_WRITE');
  const event = views[write].event!;
  assert.ok(event.kind === 'ARRAY_WRITE');
  const [forward] = motionFor(event, state(write - 1), state(write), true);
  assert.deepEqual(forward, { kind: 'flight', text: String((event.data.value as { value: string }).value), from: { code: true },
    to: { cell: { objectId: event.data.objectId, index: event.data.index } }, leaving: String((event.data.previous as { value: string }).value) });
  const [back] = motionFor(event, state(write - 1), state(write), false);
  assert.deepEqual(back, { kind: 'flight', text: forward.kind === 'flight' ? forward.text : '', from: forward.kind === 'flight' ? forward.to : { code: true }, to: { code: true } });

  const read = find('ARRAY_READ');
  const [lift] = motionFor(views[read].event, state(read - 1), state(read), true);
  assert.equal(lift.kind, 'flight');
  assert.deepEqual(lift.kind === 'flight' && lift.to, { code: true }, 'a read carries the value from its cell to the code');
  assert.deepEqual(motionFor(views[read].event, state(read - 1), state(read), false), [], 'a read changed nothing to undo');

  const declare = find('DECLARE');
  const [store] = motionFor(views[declare].event, state(declare - 1), state(declare), true);
  assert.ok(store.kind === 'flight' && 'binding' in store.to);

  const compare = views.findIndex(v => v.event?.kind === 'EXPRESSION' && v.event.data.operator === '>');
  const [badge] = motionFor(views[compare].event, state(compare - 1), state(compare), true);
  assert.ok(badge.kind === 'badge' && /^\d+ > \d+$/.test(badge.text) && typeof badge.verdict === 'boolean', JSON.stringify(badge));
  assert.deepEqual(motionFor(views[compare].event, state(compare - 1), state(compare), false), []);

  assert.deepEqual(motionFor(null, state(0), state(0), true), []);
});
