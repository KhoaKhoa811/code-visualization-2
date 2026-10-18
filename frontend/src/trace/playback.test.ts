import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { describe as describeStep, formatValue, fromBits, namesOf } from './format.ts';
import { Playback, TraceError, type State } from './playback.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const examples = new URL('../../../contracts/trace/v1/examples/', import.meta.url);
const example = (path: string): Trace => JSON.parse(readFileSync(new URL(path, examples), 'utf8'));

function array(state: State, name: string): string[] {
  const binding = state.bindings.find(b => b.name === name);
  assert.ok(binding && binding.value.kind === 'ref');
  const object = state.objects.get(binding.value.objectId);
  assert.ok(object?.kind === 'array');
  return object.elements.map(v => formatValue(v));
}

test('bubble sort plays from the recorded initial state to the sorted array', () => {
  const playback = new Playback(fixture('bubble-sort'));
  assert.equal(playback.steps, 80);
  const start = playback.at(0);
  assert.deepEqual(start.state.bindings.map(b => b.name), ['args']);
  assert.equal(start.event, null);
  const declared = playback.at(2);
  assert.deepEqual(array(declared.state, 'a'), ['3', '1', '2']);
  assert.equal(describeStep(declared.event!, declared.state), 'Declare int[] a = int[3]');
  const end = playback.at(playback.steps);
  assert.ok(end.terminal);
  assert.deepEqual(end.state.frames, []); // FRAME_END joins the final cursor of a complete trace
  assert.deepEqual(array(end.beforeEnd!, 'a'), ['1', '2', '3']); // recorded values just before main ended
  assert.equal(playback.at(playback.steps - 1).beforeEnd, null);
  const writes = Array.from({ length: playback.steps }, (_, i) => playback.at(i + 1)).filter(v => v.event?.kind === 'ARRAY_WRITE');
  assert.equal(writes.length, 4);
  const first = writes[0];
  assert.deepEqual(first.focus, { objectId: 'o2', index: 0, access: 'write' });
  assert.equal(describeStep(first.event!, first.state), 'Write a[0]: 3 → 1');
});

test('backward and checkpointed reconstruction equal a fresh forward replay', () => {
  const trace = fixture('bubble-sort');
  const plain = new Playback(trace, 1_000_000);
  const checkpointed = new Playback(trace, 4);
  for (let k = plain.steps; k >= 0; k--) {
    assert.deepEqual(checkpointed.at(k), plain.at(k), `cursor ${k}`);
  }
  assert.deepEqual(plain.at(10), plain.at(10)); // repeated reads are stable
});

test('cursor does not reveal future facts', () => {
  const playback = new Playback(fixture('bubble-sort'));
  const view = playback.at(1); // ALLOCATE before the binding is declared
  assert.equal(view.event?.kind, 'ALLOCATE');
  assert.deepEqual(view.state.bindings.map(b => b.name), ['args']);
  assert.deepEqual(namesOf(view.state, 'o2'), []);
});

test('failure, timeout prefix and unavailable capture', () => {
  const failed = new Playback(fixture('failed-write'));
  const last = failed.at(failed.steps);
  assert.equal(last.event?.kind, 'EXCEPTION');
  assert.match(describeStep(last.event!, last.state), /^java\.lang\.ArrayIndexOutOfBoundsException/);
  assert.deepEqual(array(failed.at(failed.steps - 1).state, 'a'), ['0']);

  const timeout = new Playback(fixture('timeout'));
  assert.equal(timeout.trace.capture.status, 'partial');
  assert.ok(timeout.steps >= 1);
  assert.equal(timeout.at(timeout.steps).state.frames.length, 1); // no invented cleanup after the cutoff
  assert.equal(timeout.at(timeout.steps).beforeEnd, null);

  const cancelled = new Playback(fixture('cancelled')); // cancelled while running: safe prefix
  assert.equal(cancelled.trace.execution.status, 'cancelled');
  assert.equal(cancelled.trace.capture.status, 'partial');
  cancelled.at(cancelled.steps);

  for (const name of ['compile-error', 'output-only']) {
    const p = new Playback(fixture(name));
    assert.equal(p.available, false, name);
    assert.throws(() => p.at(0), TraceError);
  }
});

test('every valid contract example loads and replays to its end', () => {
  for (const file of readdirSync(new URL('valid/', examples))) {
    const p = new Playback(example(`valid/${file}`));
    if (p.available) for (let k = 0; k <= p.steps; k++) p.at(k);
  }
  const zero = new Playback(example('valid/zero-steps.json'));
  assert.equal(zero.steps, 0);
  assert.deepEqual(zero.at(0).state.frames, []);
  const recursion = new Playback(example('valid/recursion.json'));
  const depths = Array.from({ length: recursion.steps + 1 }, (_, k) => recursion.at(k).state.frames.length);
  assert.ok(Math.max(...depths) >= 3);
});

test('inconsistent traces are rejected before playback', () => {
  for (const file of ['sequence-gap.json', 'future-boundary.json', 'dangling-reference.json']) {
    assert.throws(() => new Playback(example(`semantic-invalid/${file}`)), TraceError, file);
  }
  assert.throws(() => new Playback({ ...fixture('bubble-sort'), schemaVersion: '2.0.0' as '1.0.0' }), TraceError);
  const tampered = fixture('bubble-sort');
  const write = tampered.events.find(e => e.kind === 'ARRAY_WRITE')!;
  if (write.kind === 'ARRAY_WRITE') write.data.previous = { kind: 'int', value: '99' };
  assert.throws(() => new Playback(tampered), /disagrees with reconstructed state/);
});

test('values format without converting longs or doubles through guesses', () => {
  assert.equal(formatValue({ kind: 'long', value: '9223372036854775807' }), '9223372036854775807L');
  assert.equal(fromBits('8000000000000000'), '-0.0');
  assert.equal(fromBits('3ff8000000000000'), '1.5');
  assert.equal(fromBits('7ff0000000000000'), 'Infinity');
  assert.equal(fromBits('40490fdb').startsWith('3.14159'), true);
  assert.equal(formatValue({ kind: 'char', value: 65 }), "'A'");
  assert.equal(formatValue({ kind: 'unassigned' }), '(unassigned)');
});

test('uncaptured statements make bindings unknown and apply re-read arrays (1.1.0)', () => {
  const unknown = new Playback(fixture('uncaptured-unknown'));
  const views = Array.from({ length: unknown.steps + 1 }, (_, k) => unknown.at(k));
  const region = views.find(v => v.event?.kind === 'UNCAPTURED')!;
  assert.equal(formatValue(region.state.bindings.find(b => b.name === 'x')!.value), '?');
  assert.equal(describeStep(region.event!, region.state), 'Ran without visualization (x unknown)');
  assert.ok(region.focus.touched?.length === 1);
  const assigned = views.find(v => v.event?.kind === 'ASSIGN')!;
  assert.equal(formatValue(assigned.state.bindings.find(b => b.name === 'x')!.value), '6');

  const helper = new Playback(fixture('uncaptured-mutating-helper'));
  const bumps = Array.from({ length: helper.steps }, (_, k) => helper.at(k + 1)).filter(v => v.event?.kind === 'UNCAPTURED');
  assert.deepEqual(bumps.map(v => array(v.state, 'a')), [['2', '2'], ['3', '2'], ['3', '2']]);
  assert.match(describeStep(bumps[0].event!, bumps[0].state), /a changed/);
});

test('arrays created by uncaptured code appear on first covered use; a failure inside a region stays partial', () => {
  const created = new Playback(fixture('uncaptured-new-array'));
  const write = Array.from({ length: created.steps }, (_, k) => created.at(k + 1)).find(v => v.event?.kind === 'ARRAY_WRITE')!;
  const objectId = write.focus.objectId!;
  const object = write.state.objects.get(objectId);
  assert.ok(object?.kind === 'array');
  assert.deepEqual(object.elements.map(v => formatValue(v)), ['1', '0', '9']);

  const failure = new Playback(fixture('uncaptured-failure'));
  assert.equal(failure.trace.capture.status, 'partial');
  assert.equal(failure.at(failure.steps).event?.kind, 'UNCAPTURED');
});

test('primitive types, casts, bitwise operators and unassigned declarations play back (1.2.0)', () => {
  const types = new Playback(fixture('primitive-types'));
  const end = types.at(types.steps).beforeEnd!;
  const show = (name: string) => formatValue(end.bindings.find(b => b.name === name)!.value);
  assert.deepEqual(['big', 'b', 's', 'c', 'f', 'd', 'i', 'letter'].map(show),
    ['6000000001L', '-128', '-3', "'c'", '1.5f', '0.25', '25', "'d'"]);
  const cast = Array.from({ length: types.steps }, (_, k) => types.at(k + 1)).find(v => v.event?.kind === 'EXPRESSION'
    && v.event.data.operator === '(int)')!;
  assert.equal(describeStep(cast.event!, cast.state), '(int)25.0 = 25');

  const bits = new Playback(fixture('bitwise'));
  const ops = Array.from({ length: bits.steps }, (_, k) => bits.at(k + 1)).filter(v => v.event?.kind === 'EXPRESSION')
    .map(v => describeStep(v.event!, v.state));
  assert.ok(ops.includes('~11 = -12') && ops.includes('6 & 3 = 2') && ops.includes('(byte)344 = 88'), ops.join('\n'));

  const nan = new Playback(fixture('float-compare'));
  const compare = Array.from({ length: nan.steps }, (_, k) => nan.at(k + 1)).find(v => v.event?.kind === 'EXPRESSION')!;
  assert.equal(describeStep(compare.event!, compare.state), '0.0 / 0.0 = NaN');

  const unassigned = new Playback(fixture('uninitialized'));
  const first = unassigned.at(1);
  assert.equal(describeStep(first.event!, first.state), 'Declare int total = (unassigned)');
});
