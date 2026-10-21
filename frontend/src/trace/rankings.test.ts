import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback, type State } from './playback.ts';
import { assess, assessState, RULES, type Assessment } from './rankings.ts';
import type { ObservableEvent, Trace, TraceObject, Value } from './types.ts';

const int = (v: number): Value => ({ kind: 'int', value: String(v) });
const ref = (id: string): Value => ({ kind: 'ref', objectId: id });
const NULL: Value = { kind: 'null' };
const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

function state(objects: TraceObject[], vars: Record<string, string> = {}): State {
  return {
    objects: new Map(objects.map(o => [o.objectId, o])), scopes: [],
    frames: [{ frameId: 'f0', methodId: 'Main.main(java.lang.String[]):void', callerFrameId: null }],
    bindings: Object.entries(vars).map(([name, id]) => ({ bindingId: name, declarationId: name, frameId: 'f0', scopeId: 's0', name, javaType: '?', value: ref(id) })),
  };
}
const tree = (id: string, key: number, l: Value, r: Value): TraceObject => ({ objectId: id, kind: 'object', className: 'Main.T', fields: [
  { name: 'key', javaType: 'int', value: int(key) }, { name: 'left', javaType: 'Main.T', value: l }, { name: 'right', javaType: 'Main.T', value: r }] });
const cell = (id: string, v: number, next: Value): TraceObject => ({ objectId: id, kind: 'object', className: 'Main.C', fields: [
  { name: 'v', javaType: 'int', value: int(v) }, { name: 'next', javaType: 'Main.C', value: next }] });
const pick = (all: Assessment[], subject: string, view: string) => all.find(a => a.subject === subject && a.view === view)!;
let seq = 0;
const op = (step: number, kind: string, data: object) => ({ seq: ++seq, step, source: { start: 0, end: 0 }, frameId: 'f0', kind, data }) as unknown as ObservableEvent;

test('tree thresholds, contradictions and the separate search-order observation (10c)', () => {
  const one = assessState(state([tree('a', 5, NULL, NULL)]), [], 1);
  assert.equal(pick(one, 'Main.T', 'tree').level, 'Weak');
  const two = assessState(state([tree('a', 5, ref('b'), NULL), tree('b', 2, NULL, NULL)]), [], 2);
  assert.equal(pick(two, 'Main.T', 'tree').level, 'Possible', '2 nodes, 1 link');
  const strong = assessState(state([tree('a', 5, ref('b'), ref('c')), tree('b', 2, NULL, NULL), tree('c', 8, NULL, NULL)]), [], 3);
  const t = pick(strong, 'Main.T', 'tree');
  assert.equal(t.level, 'Strong');
  assert.equal(t.observation, 'observed: search-tree order holds');
  const unordered = assessState(state([tree('a', 5, ref('b'), ref('c')), tree('b', 9, NULL, NULL), tree('c', 8, NULL, NULL)]), [], 3);
  assert.equal(pick(unordered, 'Main.T', 'tree').level, 'Strong', 'order is an observation, not fit');
  assert.match(pick(unordered, 'Main.T', 'tree').observation!, /fails at b/);
  const shared = assessState(state([tree('a', 5, ref('b'), ref('c')), tree('b', 2, NULL, NULL), tree('c', 8, ref('b'), NULL)]), [], 3);
  assert.equal(pick(shared, 'Main.T', 'tree').level, 'Weak', 'a contradiction caps at Weak');
  assert.deepEqual(pick(shared, 'Main.T', 'tree').contradictions, ['c.left -> b']);
  const hidden = assessState(state([tree('a', 5, ref('b'), ref('c')), tree('b', 2, { kind: 'unknown' }, NULL), tree('c', 8, NULL, NULL)]), [], 3);
  assert.equal(pick(hidden, 'Main.T', 'tree').level, 'Possible', 'a missing fact caps at Possible');
  assert.deepEqual(pick(hidden, 'Main.T', 'tree').missing, ['b.left unknown']);
});

test('list thresholds and cycles; a confirmed mapping that does not fit is Incompatible (10c)', () => {
  const chain = (n: number) => Array.from({ length: n }, (_, i) => cell(`c${i}`, i, i + 1 < n ? ref(`c${i + 1}`) : NULL));
  assert.equal(pick(assessState(state(chain(1)), [], 1), 'Main.C', 'list').level, 'Weak');
  assert.equal(pick(assessState(state(chain(2)), [], 1), 'Main.C', 'list').level, 'Possible');
  assert.equal(pick(assessState(state(chain(3)), [], 1), 'Main.C', 'list').level, 'Strong');
  const loop = assessState(state([cell('a', 1, ref('b')), cell('b', 2, ref('c')), cell('c', 3, ref('a'))]), [], 1);
  assert.equal(pick(loop, 'Main.C', 'list').level, 'Weak');
  const wrong = assessState(state(chain(3)), [], 1, { structs: [{ kind: 'list', className: 'Main.C', value: 'v', next: 'v' }] });
  assert.equal(pick(wrong, 'Main.C', 'list').level, 'Incompatible');
  const absent = assessState(state([]), [], 0, { trees: [{ className: 'Main.T', value: 'key', left: 'left', right: 'right' }] });
  assert.equal(pick(absent, 'Main.T', 'tree').level, 'Unknown');
});

test('array stack and queue evidence come from recorded field writes (10c)', () => {
  const buf: TraceObject = { objectId: 'q', kind: 'object', className: 'Main.R', fields: [
    { name: 'data', javaType: 'int[]', value: ref('A') }, { name: 'head', javaType: 'int', value: int(1) }, { name: 'tail', javaType: 'int', value: int(0) }] };
  const A: TraceObject = { objectId: 'A', kind: 'array', elementType: 'int', elements: [0, 0, 0].map(int) };
  const write = (step: number, field: string, from: number, to: number) => op(step, 'FIELD_WRITE', { objectId: 'q', field, previous: int(from), value: int(to) });
  const queueOps = [write(1, 'tail', 0, 1), write(2, 'tail', 1, 2), write(3, 'head', 0, 1), write(4, 'tail', 2, 0)]; // the rear wraps
  const all = assessState(state([buf, A]), queueOps, 4);
  assert.equal(pick(all, 'Main.R', 'array queue').level, 'Strong');
  assert.equal(pick(all, 'Main.R', 'array stack').level, 'Possible', 'both are assessed; counts tell them apart');
  const backwards = assessState(state([buf, A]), [write(1, 'tail', 2, 1)], 1);
  assert.equal(pick(backwards, 'Main.R', 'array queue').level, 'Weak');
  assert.match(pick(backwards, 'Main.R', 'array queue').contradictions[0], /tail moved 2 → 1/);
  const stackOps = [write(1, 'head', 0, 1), write(2, 'head', 1, 2), write(3, 'head', 2, 1)];
  assert.equal(pick(assessState(state([buf, A]), stackOps, 3), 'Main.R', 'array stack').level, 'Strong');
});

test('heap swaps, the 3-step window, and order observed separately (10c, AC-34)', () => {
  const A: TraceObject = { objectId: 'A', kind: 'array', elementType: 'int', elements: [1, 9, 5].map(int) };
  const w = (step: number, index: number) => op(step, 'ARRAY_WRITE', { objectId: 'A', index, previous: int(0), value: int(0) });
  const s = state([A], { a: 'A' });
  const three = assessState(s, [w(1, 0), w(2, 1), w(5, 0), w(6, 2), w(9, 1), w(11, 0)], 11);
  assert.equal(pick(three, 'a', 'max-heap').level, 'Strong', 'swaps (0,1) (0,2) (1,0)');
  assert.match(pick(three, 'a', 'max-heap').observation!, /max-heap order fails at 0→1, 0→2/);
  assert.equal(pick(three, 'a', 'min-heap').level, 'Strong', 'fit does not depend on order');
  const far = assessState(s, [w(1, 0), w(4, 1)], 4);
  assert.equal(pick(far, 'a', 'max-heap').level, 'Weak', 'writes 3 steps apart are not one swap');
  assert.equal(pick(assessState(s, [w(1, 1), w(3, 3)], 3), 'a', 'max-heap').level, 'Possible');
});

test('deque calls rank stack and queue; ties are allowed (10c)', () => {
  const D: TraceObject = { objectId: 'D', kind: 'collection', className: 'java.util.ArrayDeque', elements: [] };
  const call = (step: number, name: string, args: Value[] = []) =>
    op(step, 'LIBRARY', { objectId: 'D', method: `java.util.Deque.${name}(${args.length ? 'java.lang.Object' : ''})`, arguments: args, result: null, after: null });
  const s = state([D], { d: 'D' });
  const st = assessState(s, [call(1, 'push', [int(1)]), call(2, 'push', [int(2)]), call(3, 'pop')], 3);
  assert.equal(pick(st, 'd', 'stack').level, 'Strong');
  assert.equal(pick(st, 'd', 'queue').level, 'Weak');
  const peekOnly = assessState(s, [call(1, 'peek')], 1);
  assert.equal(pick(peekOnly, 'd', 'stack').level, 'Possible');
  assert.equal(pick(peekOnly, 'd', 'queue').level, 'Possible', 'a tie: peek counts for both');
  assert.equal(pick(assessState(s, [call(1, 'remove', [int(3)])], 1), 'd', 'queue').level, 'Weak', 'remove(Object) is neither');
});

test('assessments are deterministic, rewind-safe and never read future steps (10c, AC-33)', () => {
  for (const name of ['objects', 'library']) {
    const trace = fixture(name);
    const p = new Playback(trace);
    const forward = Array.from({ length: p.steps + 1 }, (_, k) => assess(p, k));
    for (let k = p.steps; k >= 0; k--) assert.deepEqual(assess(p, k), forward[k], `${name} step ${k} on the way back`);
    for (const k of [1, Math.floor(p.steps / 3), Math.floor(p.steps / 2), p.steps - 1]) {
      const cut: Trace = { ...trace, events: trace.events.slice(0, p.boundary(k)), stepEnds: trace.stepEnds.slice(0, k),
        capture: { ...trace.capture, status: 'partial', safeEventCount: p.boundary(k) } };
      assert.deepEqual(assess(new Playback(cut), k), forward[k], `${name}: step ${k} does not depend on later steps`);
    }
  }
  assert.equal(RULES, 'rank-1');
  const end = assess(new Playback(fixture('objects')), new Playback(fixture('objects')).steps);
  assert.equal(pick(end, 'Main.Tree', 'tree').level, 'Strong');
  assert.equal(pick(end, 'Main.Node', 'list').level, 'Strong');
});
