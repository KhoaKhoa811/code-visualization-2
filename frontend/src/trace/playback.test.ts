import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { describe as describeStep, formatValue, fromBits, methodName, namesOf } from './format.ts';
import { Playback, TraceError, type State } from './playback.ts';
import type { Trace, Value } from './types.ts';

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

test('switch steps highlight the chosen label and change no state (1.3.0)', () => {
  const p = new Playback(fixture('switch'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const switches = views.filter(v => v.event?.kind === 'SWITCH');
  const text = p.trace.source.text;
  assert.deepEqual(switches.map(v => describeStep(v.event!, v.state)).slice(0, 4),
    ['Switch on 0: matching case', 'Switch on 1: matching case', 'Switch on 2: matching case', 'Switch on 3: no case matches, default']);
  const none = switches.find(v => v.event?.kind === 'SWITCH' && v.event.data.matched === 'none')!;
  assert.equal(text.slice(none.event!.source.start, none.event!.source.end), 's');
  assert.equal(describeStep(none.event!, none.state), 'Switch on 624: no case matches');
  const onChar = switches.find(v => v.event?.kind === 'SWITCH' && v.event.data.value.kind === 'char')!;
  assert.equal(text.slice(onChar.event!.source.start, onChar.event!.source.end), "case 'a', 'b'");
  assert.equal(describeStep(onChar.event!, onChar.state), "Switch on 'b': matching case");
  const before = p.at(switches[0].cursor - 1).state;
  assert.deepEqual(switches[0].state.bindings, before.bindings);
});

test('arrays of arrays keep row identities, aliases and element paths (1.3.0)', () => {
  const p = new Playback(fixture('nested-arrays'));
  const end = p.at(p.steps).beforeEnd!;
  const bound = (name: string) => {
    const b = end.bindings.find(x => x.name === name)!;
    assert.ok(b.value.kind === 'ref');
    const o = end.objects.get(b.value.objectId);
    assert.ok(o?.kind === 'array');
    return o;
  };
  const grid = bound('grid');
  assert.equal(formatValue(end.bindings.find(b => b.name === 'grid')!.value, end.objects), 'int[2][]');
  const row1 = grid.elements[1];
  assert.ok(row1.kind === 'ref');
  assert.deepEqual(namesOf(end, row1.objectId), ['grid[1]']);
  const tri = bound('tri');
  assert.deepEqual(tri.elements[0], tri.elements[1]); // tri[0] = tri[1] shares the row
  assert.equal(formatValue(bound('cube').elements[0], end.objects), 'int[2][]');
  const write = Array.from({ length: p.steps }, (_, k) => p.at(k + 1)).find(v => v.event?.kind === 'ARRAY_WRITE')!;
  assert.equal(describeStep(write.event!, write.state), 'Write grid[1][2]: 0 → 7');
  const first = p.at(1);
  assert.equal(describeStep(first.event!, first.state), 'Create int[2][]');
  assert.equal(first.state.objects.size, 4); // args, the outer array and its two rows captured in the same step
});

test('rows and strings created by uncaptured statements are captured before the step that shows them', () => {
  const p = new Playback(fixture('uncaptured-rows'));
  const region = Array.from({ length: p.steps }, (_, k) => p.at(k + 1)).find(v => v.event?.kind === 'UNCAPTURED')!;
  const g = region.state.bindings.find(b => b.name === 'g')!;
  assert.ok(g.value.kind === 'ref');
  const outer = region.state.objects.get(g.value.objectId);
  assert.ok(outer?.kind === 'array');
  const row = outer.elements[0];
  assert.ok(row.kind === 'ref');
  const created = region.state.objects.get(row.objectId);
  assert.ok(created?.kind === 'array');
  assert.deepEqual(created.elements.map(v => formatValue(v)), ['3', '4', '0']);
  assert.equal(describeStep(region.event!, region.state), 'Ran without visualization (g changed)');
});

test('calls push distinct frames, returns pop them and backward steps restore them (8d)', () => {
  const p = new Playback(fixture('methods'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const deepest = Math.max(...views.map(v => v.state.frames.length));
  assert.equal(deepest, 4); // main + factorial(3) + factorial(2) + factorial(1)
  const firstCall = views.find(v => v.event?.kind === 'CALL')!;
  assert.equal(describeStep(firstCall.event!, firstCall.state), 'Call factorial(n = 3)');
  const deep = views.find(v => v.state.frames.length === 4)!;
  const ns = deep.state.bindings.filter(b => b.name === 'n').map(b => formatValue(b.value));
  assert.deepEqual(ns, ['3', '2', '1']); // one n per invocation
  const ret = views.find(v => v.event?.kind === 'RETURN')!;
  assert.equal(describeStep(ret.event!, ret.state), 'Return 1');
  assert.equal(ret.state.frames.length, 3);
  assert.equal(p.at(ret.cursor - 1).state.frames.length, 4); // stepping back restores the callee frame
  const implicit = views.find(v => v.event?.kind === 'RETURN' && v.event.data.implicit)!;
  assert.equal(describeStep(implicit.event!, implicit.state), 'Return (end of method)');
  assert.equal(methodName('Main.len(java.lang.String[]):int'), 'len');
});

test('an uncaught failure in a callee unwinds every frame (8d)', () => {
  const p = new Playback(fixture('methods-unwind'));
  const last = p.at(p.steps);
  assert.equal(last.event?.kind, 'EXCEPTION');
  assert.deepEqual(last.state.frames, []);
  assert.equal(last.beforeEnd!.frames.length, 2); // main and boom when it failed
});

test('objects, fields and references play back with field paths (8e)', () => {
  const p = new Playback(fixture('objects'));
  const views = Array.from({ length: p.steps + 1 }, (_, k) => p.at(k));
  const created = views.find(v => v.event?.kind === 'ALLOCATE')!;
  assert.equal(describeStep(created.event!, created.state), 'Create Node o2');
  const call = views.find(v => v.event?.kind === 'CALL')!;
  assert.equal(describeStep(call.event!, call.state), 'Call new Node(this = Node o2, v = 1)');
  const write = views.find(v => v.event?.kind === 'FIELD_WRITE' && v.event.data.field === 'next')!;
  assert.equal(describeStep(write.event!, write.state), 'Write head.next: null → Node o3');
  assert.deepEqual(write.focus, { objectId: 'o2', field: 'next', access: 'write' });
  const end = p.at(p.steps).beforeEnd!;
  const third = [...end.objects.values()].find(o => o.kind === 'object' && namesOf(end, o.objectId).includes('head.next.next'));
  assert.ok(third, 'the third node is reached through head.next.next');
  assert.equal(p.at(write.cursor - 1).state.objects.get('o2')?.kind, 'object');
  const before = p.at(write.cursor - 1).state.objects.get('o2');
  assert.ok(before?.kind === 'object' && before.fields.find(f => f.name === 'next')!.value.kind === 'null'); // backward restores the field
});

test('objects from uncaptured code, cycles included, are captured within one step (8e)', () => {
  const p = new Playback(fixture('objects-uncaptured'));
  const end = p.at(p.steps).beforeEnd!;
  assert.equal(end.bindings.find(b => b.name === 'c')!.value.kind, 'unknown'); // only uncaptured code assigned c
  const read = Array.from({ length: p.steps }, (_, k) => p.at(k + 1)).find(v => v.event?.kind === 'FIELD_READ' && v.event.data.field === 'other'
    && v.event.data.value.kind === 'ref' && v.state.objects.get(v.event.data.objectId)?.kind === 'object'
    && namesOf(v.state, v.event.data.objectId).length === 0)!; // c.other: the object c refers to has no name
  const c = { value: { kind: 'ref' as const, objectId: read.focus.objectId! } };
  const first = end.objects.get(c.value.objectId);
  assert.ok(first?.kind === 'object');
  const other = first.fields.find(f => f.name === 'other')!.value;
  assert.ok(other.kind === 'ref');
  const second = end.objects.get(other.objectId);
  assert.ok(second?.kind === 'object');
  assert.deepEqual(second.fields.find(f => f.name === 'other')!.value, { kind: 'ref', objectId: c.value.objectId }); // a cycle
  const bump = Array.from({ length: p.steps }, (_, k) => p.at(k + 1)).find(v => v.event?.kind === 'UNCAPTURED' && v.event.data.objects.length > 0)!;
  assert.match(describeStep(bump.event!, bump.state), /changed/);
});

test('library calls replace collection contents and read as calls (8f)', () => {
  const p = new Playback(fixture('library'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const calls = views.filter(v => v.event?.kind === 'LIBRARY').map(v => describeStep(v.event!, v.state));
  assert.ok(calls.includes('xs.add(5) → true'), calls.join('\n'));
  assert.ok(calls.includes('stack.pop() → 2'), calls.join('\n'));
  assert.ok(calls.includes('sb.toString() → "xZ3ba"'), calls.join('\n'));
  const pop = views.find(v => v.event?.kind === 'LIBRARY' && describeStep(v.event, v.state) === 'stack.pop() → 2')!;
  const stackId = pop.focus.objectId!;
  const before = p.at(pop.cursor - 1).state.objects.get(stackId);
  const after = pop.state.objects.get(stackId);
  assert.ok(before?.kind === 'collection' && after?.kind === 'collection');
  assert.deepEqual(before.elements.map(v => formatValue(v)), ['2', '1']);
  assert.deepEqual(after.elements.map(v => formatValue(v)), ['1']); // backward restores the popped element
  const end = p.at(p.steps).beforeEnd!;
  const pq = [...end.objects.values()].find(o => o.kind === 'collection' && o.className === 'java.util.PriorityQueue');
  assert.ok(pq?.kind === 'collection');
  assert.deepEqual(pq.elements.map(v => formatValue(v)), ['3', '5']); // poll order, never the heap layout
  const counts = [...end.objects.values()].find(o => o.kind === 'map');
  assert.ok(counts?.kind === 'map');
  assert.deepEqual(counts.entries.map(e => `${formatValue(e.key, end.objects)}=${formatValue(e.value)}`), ['"a"=2', '"b"=1', '"c"=1']);
});

test('library sorts change their argument in one step; reverse order shows poll order (10d)', () => {
  const p = new Playback(fixture('array-utilities'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const calls = views.filter(v => v.event?.kind === 'LIBRARY').map(v => describeStep(v.event!, v.state));
  assert.ok(calls.includes('Arrays.sort(a)'), calls.join('\n'));
  assert.ok(calls.includes('Arrays.toString(a) → "[1, 2, 3]"'), calls.join('\n'));
  assert.ok(calls.includes('Collections.sort(xs)'), calls.join('\n'));
  const sort = views.find(v => v.event?.kind === 'LIBRARY' && describeStep(v.event, v.state) === 'Arrays.sort(a)')!;
  const arrayId = sort.focus.objectId!;
  assert.equal(sort.focus.access, 'write');
  const before = p.at(sort.cursor - 1).state.objects.get(arrayId), after = sort.state.objects.get(arrayId);
  assert.ok(before?.kind === 'array' && after?.kind === 'array');
  assert.deepEqual(before.elements.map(v => formatValue(v)), ['3', '1', '2']); // backward restores the unsorted array
  assert.deepEqual(after.elements.map(v => formatValue(v)), ['1', '2', '3']);
  const split = views.find(v => v.event?.kind === 'LIBRARY' && v.event.data.method.endsWith('split(java.lang.String)'))!;
  const words = split.state.objects.get((split.event!.data as { result: { objectId: string } }).result.objectId);
  assert.ok(words?.kind === 'array');
  assert.deepEqual(words.elements.map(v => formatValue(v, split.state.objects)), ['"y"', '"x"', '"y"']);

  const r = new Playback(fixture('reverse-order'));
  const end = r.at(r.steps).beforeEnd!;
  const pq = [...end.objects.values()].find(o => o.kind === 'collection' && o.className === 'java.util.PriorityQueue');
  assert.ok(pq?.kind === 'collection');
  assert.deepEqual(pq.elements.map(v => formatValue(v)), ['3', '1']); // reverse order: the largest polls first
});

test('for-each over collections and map views reads as next-element steps; entries keep key and value (1.9.0)', () => {
  const p = new Playback(fixture('for-each'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const texts = views.filter(v => v.event?.kind === 'LIBRARY').map(v => describeStep(v.event!, v.state));
  for (const want of ['Next element of xs: 3', 'Next element of seen: "a"', "Next key of m: 'x'", 'Next value of m: 2', "Next entry of m: 'y'=2", 'Next element of d: 2'])
    assert.ok(texts.includes(want), `${want}\n${texts.join('\n')}`);
  const entry = views.find(v => v.event?.kind === 'DECLARE' && v.event.data.binding.name === 'e')!;
  assert.equal(formatValue(entry.state.bindings.find(b => b.name === 'e')!.value, entry.state.objects), "'x'=1");
  const next = views.find(v => v.event?.kind === 'LIBRARY' && describeStep(v.event, v.state) === 'Next element of xs: 3')!;
  assert.deepEqual(next.focus, { objectId: (next.event!.data as { objectId: string }).objectId, access: 'read' });
});

test('lambdas run by library calls are frames via the call; functions are values (1.10.0)', () => {
  const p = new Playback(fixture('lambdas'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const texts = views.map(v => describeStep(v.event!, v.state));
  for (const want of ['Call lambda at 6:12 via forEach(total = int[1], x = 3)', 'xs.forEach(lambda at 6:12)',
    'xs.removeIf(lambda at 7:13) → true', 'Function.apply(2) → 4'])
    assert.ok(texts.includes(want), `${want}\n${texts.join('\n')}`);
  const inside = views.find(v => v.event?.kind === 'CALL' && v.event.data.frame.via)!;
  assert.equal(inside.state.frames.length, 2);
  assert.equal(inside.state.frames[1].via, 'java.util.List.forEach(java.util.function.Consumer)');
  const after = views.find(v => v.event?.kind === 'LIBRARY' && v.event.data.method.startsWith('java.util.List.forEach'))!;
  assert.equal(after.state.frames.length, 1); // the callbacks ended before their library call's step
  assert.equal(formatValue(after.state.bindings.find(b => b.name === 'total')!.value, after.state.objects), 'int[1]');
});

test('stream pipelines run their lambdas via the terminal call; streams and optionals are values (1.10.0)', () => {
  const p = new Playback(fixture('streams'));
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const texts = views.map(v => describeStep(v.event!, v.state));
  for (const want of ['Call lambda at 5:28 via sum(x = 1)', 'Call lambda at 5:54 via sum(x = 2)',
    'IntStream.sum() → 20', 'Declare java.util.OptionalInt top = OptionalInt[30]'])
    assert.ok(texts.includes(want), `${want}\n${texts.join('\n')}`);
  const filter = views.find(v => v.event?.kind === 'LIBRARY' && v.event.data.method.startsWith('java.util.stream.Stream.filter'))!;
  assert.equal(formatValue(filter.event!.data.result as Value), 'ArrayList.stream().filter(lambda at 5:28)'); // lazy: nothing ran yet
});

test('Scanner reads are recorded library steps over console input (8g)', () => {
  const p = new Playback(example('valid/scanner-read.json'));
  const created = p.at(1);
  assert.equal(describeStep(created.event!, created.state), 'Create Scanner(System.in)');
  const declared = p.at(2);
  assert.equal(describeStep(declared.event!, declared.state), 'Declare java.util.Scanner in = Scanner(System.in)');
  const read = p.at(3);
  assert.equal(describeStep(read.event!, read.state), 'Read input: in.nextInt() → 21');
  assert.deepEqual(read.focus, { objectId: 'in1', access: 'read' });
  assert.equal(formatValue(p.at(4).beforeEnd!.bindings.find(b => b.name === 'n')!.value), '21');
  assert.throws(() => new Playback({ ...example('valid/scanner-read.json'), schemaVersion: '1.5.0' as '1.12.0' }), TraceError);
  // 1.8.0: a BufferedReader over System.in is the same kind of input object.
  const reader = structuredClone(example('valid/scanner-read.json'));
  const allocate = reader.events[0];
  if (allocate.kind === 'ALLOCATE' && allocate.data.object.kind === 'input') allocate.data.object.className = 'java.io.BufferedReader';
  const r = new Playback(reader);
  assert.equal(describeStep(r.at(1).event!, r.at(1).state), 'Create BufferedReader(System.in)');
});

test('a thrown exception unwinds its frame and a catch declares it (8h)', () => {
  const trace = example('valid/try-catch.json');
  const p = new Playback(trace);
  const views = Array.from({ length: p.steps }, (_, k) => p.at(k + 1));
  const thrown = views.find(v => v.event?.kind === 'EXCEPTION')!;
  assert.equal(describeStep(thrown.event!, thrown.state, trace.source.text), 'Throw IllegalArgumentException: negative');
  assert.equal(thrown.state.frames.length, 2, 'the helper is still active at its throw');
  const caught = views.find(v => v.event?.kind === 'CATCH')!;
  assert.equal(caught.state.frames.length, 1, 'the helper frame ended while the exception left it');
  assert.equal(describeStep(caught.event!, caught.state), 'Catch e = IllegalArgumentException: negative');
  const e = caught.state.bindings.find(b => b.name === 'e')!;
  assert.equal(formatValue(e.value, caught.state.objects), 'IllegalArgumentException("negative")');
  assert.deepEqual(caught.focus, { bindingId: e.bindingId });
  assert.equal(p.at(caught.cursor - 1).state.bindings.some(b => b.name === 'e'), false, 'backward removes the catch binding');
  const broken = structuredClone(trace);
  const exception = broken.events.find(event => event.kind === 'EXCEPTION')!;
  if (exception.kind === 'EXCEPTION') exception.data.objectId = 'args0';
  assert.throws(() => new Playback(broken), TraceError);
});

test('concatenation is one step and uncaptured changes to collections are re-read (8f)', () => {
  const s = new Playback(fixture('strings'));
  const first = Array.from({ length: s.steps }, (_, k) => s.at(k + 1)).find(v => v.event?.kind === 'EXPRESSION' && v.event.data.operands.length > 2)!;
  assert.equal(describeStep(first.event!, first.state), `"Hi " + "Ada" + ", " + 36 + '!' = "Hi Ada, 36!"`);
  assert.equal(formatValue(first.event!.kind === 'EXPRESSION' ? first.event.data.result : { kind: 'null' }, first.state.objects), '"Hi Ada, 36!"');
  const r = new Playback(fixture('library-region'));
  const regions = Array.from({ length: r.steps }, (_, k) => r.at(k + 1)).filter(v => v.event?.kind === 'UNCAPTURED');
  assert.equal(regions.length, 2);
  const list = [...regions[1].state.objects.values()].find(o => o.kind === 'collection');
  assert.ok(list?.kind === 'collection');
  assert.deepEqual(list.elements.map(v => formatValue(v)), ['1', '2', '3']);
  assert.match(describeStep(regions[1].event!, regions[1].state), /xs changed/);
});
