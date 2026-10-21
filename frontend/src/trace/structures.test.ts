import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { State } from './playback.ts';
import { projectList, projectQueue, projectStack, structProblem, suggestStructs, type StructMapping } from './structures.ts';
import type { TraceObject, Value } from './types.ts';

const int = (v: number): Value => ({ kind: 'int', value: String(v) });
const ref = (id: string): Value => ({ kind: 'ref', objectId: id });
const NULL: Value = { kind: 'null' };

function state(objects: TraceObject[]): State {
  return { objects: new Map(objects.map(o => [o.objectId, o])), frames: [], scopes: [], bindings: [] };
}
const cell = (id: string, item: number, after: Value): TraceObject => ({ objectId: id, kind: 'object', className: 'Main.Cell', fields: [
  { name: 'item', javaType: 'int', value: int(item) }, { name: 'after', javaType: 'Main.Cell', value: after }] });
const array = (id: string, values: number[]): TraceObject => ({ objectId: id, kind: 'array', elementType: 'int', elements: values.map(int) });
const buffer = (id: string, data: string, ints: Record<string, number>): TraceObject => ({ objectId: id, kind: 'object', className: 'Main.Buffer', fields: [
  { name: 'data', javaType: 'int[]', value: ref(data) }, ...Object.entries(ints).map(([name, v]) => ({ name, javaType: 'int', value: int(v) }))] });

const LIST: StructMapping = { kind: 'list', className: 'Main.Cell', value: 'item', next: 'after' };

test('nonstandard field names are suggested from types and drawn once confirmed (10b, AC-27)', () => {
  const fields = new Map([
    ['Main.Cell', [{ name: 'item', javaType: 'int' }, { name: 'after', javaType: 'Main.Cell' }]],
    ['Main.Buffer', [{ name: 'data', javaType: 'int[]' }, { name: 'head', javaType: 'int' }, { name: 'tail', javaType: 'int' }]],
    ['Main.Pile', [{ name: 'slots', javaType: 'int[]' }, { name: 'n', javaType: 'int' }]],
    ['Main.Node', [{ name: 'v', javaType: 'int' }, { name: 'l', javaType: 'Main.Node' }, { name: 'r', javaType: 'Main.Node' }]],
  ]);
  assert.deepEqual(suggestStructs(fields), [
    LIST,
    { kind: 'queue', className: 'Main.Buffer', storage: 'data', front: 'head', rear: 'tail', rearIs: 'next', size: null },
    { kind: 'stack', className: 'Main.Pile', storage: 'slots', top: 'n', topIs: 'count' },
  ], 'a class with two links is a tree suggestion, not a list');
  const s = state([cell('o1', 3, ref('o2')), cell('o2', 1, ref('o3')), cell('o3', 4, NULL)]);
  const { chains, conflicts } = projectList(s, LIST as StructMapping & { kind: 'list' });
  assert.deepEqual(chains.map(c => c.nodes.map(n => n.value.kind === 'int' ? n.value.value : '?')), [['3', '1', '4']]);
  assert.deepEqual(chains[0].end, { kind: 'null' });
  assert.deepEqual(conflicts, []);
});

test('cycles and shared nodes are reported, not repaired (10b)', () => {
  const cyc = projectList(state([cell('o1', 1, ref('o2')), cell('o2', 2, ref('o1'))]), LIST as StructMapping & { kind: 'list' });
  assert.equal(cyc.chains.length, 1);
  assert.deepEqual(cyc.chains[0].end, { kind: 'repeat', objectId: 'o1' });
  assert.match(cyc.conflicts.join(), /cycle back to o1/);
  const shared = projectList(state([cell('o1', 1, ref('o3')), cell('o2', 2, ref('o3')), cell('o3', 3, NULL)]), LIST as StructMapping & { kind: 'list' });
  assert.match(shared.conflicts.join(), /o3 is next of o1 and o2/);
  assert.equal(shared.chains.flatMap(c => c.nodes).length, 3, 'every node is drawn once');
});

test('array stacks and circular queues follow the recorded fields; ambiguity is said (10b)', () => {
  const stack = projectStack(state([array('A', [7, 8, 9, 0]), buffer('o1', 'A', { n: 3 })]),
    { kind: 'stack', className: 'Main.Buffer', storage: 'data', top: 'n', topIs: 'count' })[0];
  assert.deepEqual(stack.occupied, [0, 1, 2]);
  assert.equal(stack.top, 2);
  const asIndex = projectStack(state([array('A', [7, 8, 9, 0]), buffer('o1', 'A', { n: 3 })]),
    { kind: 'stack', className: 'Main.Buffer', storage: 'data', top: 'n', topIs: 'index' })[0];
  assert.deepEqual(asIndex.occupied, [0, 1, 2, 3]);
  const bad = projectStack(state([array('A', [7]), buffer('o1', 'A', { n: 5 })]),
    { kind: 'stack', className: 'Main.Buffer', storage: 'data', top: 'n', topIs: 'count' })[0];
  assert.match(bad.notes[0], /outside the storage/);
  const Q: StructMapping & { kind: 'queue' } = { kind: 'queue', className: 'Main.Buffer', storage: 'data', front: 'head', rear: 'tail', rearIs: 'next', size: null };
  const wrapped = projectQueue(state([array('A', [5, 0, 0, 3, 4]), buffer('o1', 'A', { head: 3, tail: 1 })]), Q)[0];
  assert.deepEqual(wrapped.occupied, [3, 4, 0], 'front to rear around the end of the array');
  const meet = projectQueue(state([array('A', [1, 2]), buffer('o1', 'A', { head: 1, tail: 1 })]), Q)[0];
  assert.deepEqual(meet.occupied, []);
  assert.match(meet.notes[0], /empty or full/);
  const sized = projectQueue(state([array('A', [1, 2]), buffer('o1', 'A', { head: 1, tail: 1, count: 2 })]), { ...Q, size: 'count' })[0];
  assert.deepEqual(sized.occupied, [1, 0], 'a size field decides full');
});

test('a mapping is checked against every run and says why it no longer fits (10b, AC-30)', () => {
  const now = new Map([['Main.Cell', [{ name: 'item', javaType: 'int' }, { name: 'link', javaType: 'Main.Cell' }]]]);
  assert.match(structProblem(LIST, now)!, /no field after/, 'a renamed field');
  const retyped = new Map([['Main.Cell', [{ name: 'item', javaType: 'int' }, { name: 'after', javaType: 'java.lang.String' }]]]);
  assert.match(structProblem(LIST, retyped)!, /after is not a Main.Cell field/, 'a changed type');
  assert.match(structProblem(LIST, new Map())!, /no Main.Cell objects/);
  const q = new Map([['Main.Buffer', [{ name: 'data', javaType: 'int[]' }, { name: 'head', javaType: 'int' }]]]);
  assert.match(structProblem({ kind: 'queue', className: 'Main.Buffer', storage: 'data', front: 'head', rear: 'head', rearIs: 'next', size: null }, q)!, /different/);
});
