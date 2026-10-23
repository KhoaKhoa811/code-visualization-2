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

test('a call slides its frame in and carries the arguments; a return carries the result to the caller (11c)', () => {
  const p = new Playback(fixture('methods'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const state = (k: number) => views[k].beforeEnd ?? views[k].state;
  const call = views.findIndex(v => v.event?.kind === 'CALL' && v.event.data.parameters.length > 0);
  const event = views[call].event!;
  assert.ok(event.kind === 'CALL');
  const forward = motionFor(event, state(call - 1), state(call), true);
  assert.deepEqual(forward[0], { kind: 'enter', frame: event.data.frame.frameId });
  assert.deepEqual(forward.slice(1).map(m => m.kind === 'flight' && m.to), event.data.parameters.map(b => ({ binding: b.bindingId })));
  assert.ok(motionFor(event, state(call - 1), state(call), false).every(m => m.kind === 'flight' && 'code' in m.to), 'backward: arguments go back');

  const ret = views.findIndex(v => v.event?.kind === 'RETURN' && v.event.data.result !== null);
  const [back] = motionFor(views[ret].event, state(ret - 1), state(ret), true);
  const caller = state(ret).frames.at(-1)!;
  assert.ok(back.kind === 'flight' && 'code' in back.from);
  assert.deepEqual(back.kind === 'flight' && back.to, { frame: caller.frameId });
  assert.notEqual(caller.frameId, views[ret].event!.frameId, 'the result goes to the caller, not the returning frame');
});
test('collections: an added item pops in and its value flies in; a removed one flies out of the box (11d)', () => {
  const p = new Playback(fixture('library'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const state = (k: number) => views[k].beforeEnd ?? views[k].state;
  const count = (k: number, id: string) => { const o = state(k).objects.get(id); return o?.kind === 'collection' ? o.elements.length : -1; };
  const lib = (k: number) => { const e = views[k].event; return e?.kind === 'LIBRARY' && e.data.objectId && e.data.after ? e.data.objectId : null; };
  const grow = views.findIndex((_, k) => k > 0 && lib(k) !== null && count(k, lib(k)!) === count(k - 1, lib(k)!) + 1);
  const shrink = views.findIndex((_, k) => k > 0 && lib(k) !== null && count(k, lib(k)!) === count(k - 1, lib(k)!) - 1 && count(k, lib(k)!) >= 0);
  assert.ok(grow > 0 && shrink > 0);

  const id = lib(grow)!;
  const [appear, fly] = motionFor(views[grow].event, state(grow - 1), state(grow), true);
  assert.equal(appear.kind, 'appear');
  assert.ok(fly.kind === 'flight' && 'code' in fly.from && 'item' in fly.to && fly.to.item.objectId === id);
  const [undo] = motionFor(views[grow].event, state(grow - 1), state(grow), false);
  assert.ok(undo.kind === 'flight' && 'box' in undo.from && 'code' in undo.to, 'backward: the item goes back to the code');

  const [out] = motionFor(views[shrink].event, state(shrink - 1), state(shrink), true);
  assert.ok(out.kind === 'flight' && 'box' in out.from && out.from.box === lib(shrink) && 'code' in out.to, JSON.stringify(out));

  // A call that only reads (no `after`) moves nothing.
  const read = views.findIndex(v => v.event?.kind === 'LIBRARY' && v.event.data.after === null);
  assert.deepEqual(motionFor(views[read].event, state(read - 1), state(read), true), []);
});

test('objects: a new object appears, a field write flies into the field and its tree node pulses (11d)', () => {
  const p = new Playback(fixture('objects'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const state = (k: number) => views[k].beforeEnd ?? views[k].state;
  const alloc = views.findIndex(v => v.event?.kind === 'ALLOCATE');
  const id = (views[alloc].event!.data as { object: { objectId: string } }).object.objectId;
  assert.deepEqual(motionFor(views[alloc].event, state(alloc - 1), state(alloc), true), [{ kind: 'appear', at: { box: id } }, { kind: 'appear', at: { treeNode: id } }]);
  const write = views.findIndex(v => v.event?.kind === 'FIELD_WRITE');
  const event = views[write].event!;
  assert.ok(event.kind === 'FIELD_WRITE');
  const [fly, pulse] = motionFor(event, state(write - 1), state(write), true);
  assert.ok(fly.kind === 'flight' && 'field' in fly.to && fly.to.field.name === event.data.field);
  assert.deepEqual(pulse, { kind: 'pulse', at: { treeNode: event.data.objectId } });
});