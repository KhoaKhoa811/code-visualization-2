import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { State } from './playback.ts';
import { mappingProblem, positions, projectTree, suggestTrees, type TreeNode } from './tree.ts';
import type { TraceObject, Value } from './types.ts';

const int = (v: number): Value => ({ kind: 'int', value: String(v) });
const ref = (id: string): Value => ({ kind: 'ref', objectId: id });
const NULL: Value = { kind: 'null' };

function node(id: string, key: Value, left: Value, right: Value): TraceObject {
  return { objectId: id, kind: 'object', className: 'Main.Node', fields: [
    { name: 'key', javaType: 'int', value: key },
    { name: 'left', javaType: 'Main.Node', value: left },
    { name: 'right', javaType: 'Main.Node', value: right },
  ] };
}

function state(objects: TraceObject[], rootBinding?: string): State {
  return {
    objects: new Map(objects.map(o => [o.objectId, o])), frames: [], scopes: [],
    bindings: rootBinding ? [{ bindingId: 'b1', declarationId: 'd', frameId: 'f0', scopeId: 's0', name: 'root', javaType: 'Main.Node', value: ref(rootBinding) }] : [],
  };
}

const MAPPING = { className: 'Main.Node', value: 'key', left: 'left', right: 'right' };
const FIELDS = new Map([['Main.Node', [{ name: 'key', javaType: 'int' }, { name: 'left', javaType: 'Main.Node' }, { name: 'right', javaType: 'Main.Node' }]]]);

const inorder = (n: TreeNode): string[] => [
  ...(n.left.kind === 'node' ? inorder(n.left.node) : []),
  n.value.kind === 'int' ? n.value.value : '?',
  ...(n.right.kind === 'node' ? inorder(n.right.node) : []),
];

test('a confirmed mapping projects a binary search tree in order (9b)', () => {
  const s = state([node('o1', int(5), ref('o2'), ref('o3')), node('o2', int(2), NULL, NULL), node('o3', int(8), ref('o4'), NULL),
    node('o4', int(6), NULL, NULL)], 'o1');
  const p = projectTree(s, MAPPING, ['o1']);
  assert.equal(p.roots.length, 1);
  assert.deepEqual(inorder(p.roots[0]), ['2', '5', '6', '8']);
  assert.deepEqual(p.conflicts, []);
  const xy = positions(p);
  assert.deepEqual(xy.get('o1'), { x: 1, y: 0 });
  assert.deepEqual(xy.get('o4'), { x: 2, y: 2 }, 'in-order index and depth');
});

test('nodes not linked yet are their own trees, and shared links or cycles are reported, never duplicated (9b, AC-34)', () => {
  const fresh = projectTree(state([node('o1', int(5), NULL, NULL), node('o2', int(9), NULL, NULL)], 'o1'), MAPPING, ['o1']);
  assert.deepEqual(fresh.roots.map(r => r.objectId), ['o1', 'o2'], 'a node just created is a separate tree until linked');
  const shared = projectTree(state([node('o1', int(5), ref('o2'), ref('o3')), node('o2', int(2), NULL, NULL), node('o3', int(8), ref('o2'), NULL)]), MAPPING);
  assert.deepEqual(shared.conflicts, ['o3.left -> o2']);
  const drawn = new Set<string>();
  const collect = (n: TreeNode) => { assert.ok(!drawn.has(n.objectId)); drawn.add(n.objectId); for (const c of [n.left, n.right]) if (c.kind === 'node') collect(c.node); };
  shared.roots.forEach(collect);
  assert.equal(drawn.size, 3);
  const cycle = projectTree(state([node('o1', int(1), ref('o2'), NULL), node('o2', int(2), ref('o1'), NULL)]), MAPPING);
  assert.equal(cycle.roots.length, 1, 'a pure cycle still shows its nodes');
  assert.deepEqual(cycle.conflicts.length, 1);
  const unknown = projectTree(state([node('o1', { kind: 'unknown' }, { kind: 'unknown' }, NULL)]), MAPPING);
  assert.equal(unknown.roots[0].left.kind, 'unknown', 'unknown stays unknown, not null');
});

test('suggestions need two fields of the class itself and are checked against each run (9b, AC-30)', () => {
  assert.deepEqual(suggestTrees(FIELDS), [MAPPING]);
  const list = new Map([['Main.Item', [{ name: 'value', javaType: 'int' }, { name: 'next', javaType: 'Main.Item' }]]]);
  assert.deepEqual(suggestTrees(list), [], 'one link is a list, not a tree');
  const named = new Map([['Main.T', [{ name: 'a', javaType: 'Main.T' }, { name: 'data', javaType: 'java.lang.String' }, { name: 'b', javaType: 'Main.T' }]]]);
  assert.deepEqual(suggestTrees(named), [{ className: 'Main.T', value: 'data', left: 'a', right: 'b' }]);
  assert.equal(mappingProblem(MAPPING, FIELDS), null);
  assert.match(mappingProblem({ ...MAPPING, left: 'lo' }, FIELDS)!, /no field lo/);
  assert.match(mappingProblem({ ...MAPPING, left: 'key' }, FIELDS)!, /not a Main.Node field/);
  assert.match(mappingProblem({ ...MAPPING, right: 'left' }, FIELDS)!, /different/);
  assert.match(mappingProblem(MAPPING, new Map())!, /no Main.Node objects/);
});
