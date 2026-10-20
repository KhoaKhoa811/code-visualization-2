import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { State } from './playback.ts';
import { heapPositions, heapProblem, heapVariables, projectHeap, type HeapMapping } from './heap.ts';
import type { Binding, Trace, Value } from './types.ts';

const int = (v: number): Value => ({ kind: 'int', value: String(v) });

function state(values: number[], vars: Record<string, Value>, frames = ['f0']): State {
  const bindings: Binding[] = [{ bindingId: 'ba', declarationId: 'a', frameId: frames[0], scopeId: 's', name: 'a', javaType: 'int[]', value: { kind: 'ref', objectId: 'A' } }];
  for (const [name, value] of Object.entries(vars)) {
    bindings.push({ bindingId: `b${name}`, declarationId: name, frameId: frames[frames.length - 1], scopeId: 's', name, javaType: 'int', value });
  }
  return {
    objects: new Map([['A', { objectId: 'A', kind: 'array', elementType: 'int', elements: values.map(int) }]]),
    frames: frames.map((id, i) => ({ frameId: id, methodId: i ? 'Main.sift(int[],int,int):void' : 'Main.main(java.lang.String[]):void', callerFrameId: i ? frames[i - 1] : null })),
    scopes: [], bindings,
  };
}

const MAX: HeapMapping = { array: 'a', base: 0, size: 'end', bound: 'count', order: 'max' };

test('the boundary comes from the confirmed variable; the rest is outside the heap (9c, AC-31)', () => {
  const h = projectHeap(state([9, 7, 5, 1, 12, 15], { end: int(4) }), MAX)!;
  assert.deepEqual(h.heap, [0, 1, 2, 3]);
  assert.equal(h.boundary, 4);
  assert.deepEqual(h.violations, []);
  assert.equal(h.array.objectId, 'A', 'the heap is the same array object');
  const last = projectHeap(state([9, 7, 5, 1, 12, 15], { end: int(4) }), { ...MAX, bound: 'last' })!;
  assert.deepEqual(last.heap, [0, 1, 2, 3, 4], 'a last index includes itself');
  const whole = projectHeap(state([9, 7, 5], {}), { ...MAX, size: null })!;
  assert.deepEqual(whole.heap, [0, 1, 2]);
});

test('out of scope or unknown bounds show no boundary instead of a guess (9c)', () => {
  const missing = projectHeap(state([1, 2, 3], {}), MAX)!;
  assert.equal(missing.boundary, null);
  assert.match(missing.boundaryNote!, /end is not in scope/);
  const unknown = projectHeap(state([1, 2, 3], { end: { kind: 'unknown' } }), MAX)!;
  assert.match(unknown.boundaryNote!, /end is unknown/);
  assert.equal(projectHeap(state([1], {}), { ...MAX, array: 'b' }), null, 'no array variable in scope');
});

test('heap order is observed, never enforced, and 1-based indexing skips index 0 (9c, AC-34)', () => {
  const broken = projectHeap(state([2, 9, 5, 1], { end: int(4) }), MAX)!;
  assert.deepEqual(broken.violations, [[0, 1], [0, 2]], 'the recorded state stays as it is');
  assert.deepEqual(projectHeap(state([2, 9, 5, 1], { end: int(4) }), { ...MAX, order: 'min' })!.violations, [[1, 3]]);
  const one = projectHeap(state([0, 9, 7, 8, 3], { end: int(4) }), { ...MAX, base: 1 })!;
  assert.deepEqual(one.heap, [1, 2, 3, 4]);
  assert.deepEqual(one.violations, []);
  const pos = heapPositions({ ...MAX, base: 1 }, one.heap);
  assert.deepEqual(pos.get(1), { x: 2, y: 0 }, 'root above, in-order across');
  assert.deepEqual(pos.get(4), { x: 0, y: 2 });
  // The size variable is looked up from main inward: a helper's own n does not replace main's end.
  const nested = projectHeap(state([9, 7, 5, 1], { end: int(3) }, ['f0', 'f1']), MAX)!;
  assert.equal(nested.boundary, 3);
});

test('mapping variables come from the recorded declarations of each run (9c)', () => {
  const trace = { initialState: { bindings: [] }, events: [
    { kind: 'DECLARE', data: { binding: { name: 'a', javaType: 'int[]' } } },
    { kind: 'DECLARE', data: { binding: { name: 'names', javaType: 'java.lang.String[]' } } },
    { kind: 'CALL', data: { parameters: [{ name: 'n', javaType: 'int' }, { name: 'h', javaType: 'double[]' }] } },
  ] } as unknown as Trace;
  assert.deepEqual(heapVariables(trace), { arrays: ['a', 'h'], ints: ['n'] });
  assert.equal(heapProblem({ ...MAX, size: 'n' }, trace), null);
  assert.match(heapProblem(MAX, trace)!, /no int variable end/);
  assert.match(heapProblem({ ...MAX, array: 'names', size: null }, trace)!, /no numeric array variable names/);
});
