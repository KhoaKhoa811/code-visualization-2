import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { State } from './playback.ts';
import type { Value } from './types.ts';
import { markers, nodeValues, projectGraph, touched, type GraphMapping } from './graph.ts';

const int = (v: number): Value => ({ kind: 'int', value: String(v) });

/** A state where `g` refers to an int matrix with these rows. */
function matrix(rows: (number | null | 'unknown')[][], elementType = 'int[]'): State {
  const objects: State['objects'] = new Map();
  objects.set('m', { objectId: 'm', kind: 'array', elementType, elements: rows.map((_, i) => ({ kind: 'ref', objectId: `r${i}` })) } as never);
  rows.forEach((r, i) => objects.set(`r${i}`, {
    objectId: `r${i}`, kind: 'array', elementType: elementType.slice(0, -2),
    elements: r.map(v => v === 'unknown' ? { kind: 'unknown' } : v === null ? { kind: 'null' } : int(v)),
  } as never));
  return {
    objects, frames: [{ frameId: 'f0', methodId: 'Main.main(java.lang.String[]):void', callerFrameId: null }], scopes: [],
    bindings: [{ bindingId: 'b1', declarationId: 'd', frameId: 'f0', scopeId: 's1', name: 'g', javaType: 'int[][]', value: { kind: 'ref', objectId: 'm' } }],
  } as State;
}

const m = (over: Partial<GraphMapping> = {}): GraphMapping => ({ array: 'g', directed: true, noEdge: -1, start: 0, ...over });

test('a matrix cell other than the no-edge value is an edge with its value as weight (14a)', () => {
  const s = matrix([[-1, 4, -1], [-1, -1, 0], [-1, -1, -1]]);
  const p = projectGraph(s, m());
  assert.ok(p && 'graph' in p);
  assert.deepEqual(p.graph.edges.map(e => [e.from, e.to, (e.weight as { value: string }).value]), [[0, 1, '4'], [1, 2, '0']], '0 is an edge when -1 means none');
  assert.equal(p.graph.weighted, true);
  // With 0 as "no edge", the 0 cell is not an edge and -1 cells are.
  const zero = projectGraph(matrix([[0, 1], [0, 0]]), m({ noEdge: 0 }));
  assert.ok(zero && 'graph' in zero);
  assert.deepEqual(zero.graph.edges.map(e => [e.from, e.to]), [[0, 1]]);
  assert.equal(zero.graph.weighted, false, 'all weights 1: none drawn');
});

test('levels grow from the start node; edges outside the level tree and unreached parts are kept (14a)', () => {
  // 0 - 1, 0 - 2, 1 - 2 (undirected triangle), 3 - 4 apart from it.
  const e = -1;
  const s = matrix([[e, 1, 1, e, e], [1, e, 1, e, e], [1, 1, e, e, e], [e, e, e, e, 1], [e, e, e, 1, e]]);
  const p = projectGraph(s, m({ directed: false }));
  assert.ok(p && 'graph' in p);
  const g = p.graph;
  assert.equal(g.symmetric, true);
  assert.deepEqual(g.edges.map(x => [x.from, x.to, x.tree]), [[0, 1, true], [0, 2, true], [1, 2, false], [3, 4, true]]);
  assert.deepEqual(g.place.map(x => x.level), [0, 1, 1, 2, 3], 'the part 0 cannot reach starts below it');
  assert.deepEqual(g.roots, [0, 3]);
  // Another start node changes only the layout.
  const from2 = projectGraph(s, m({ directed: false, start: 2 }));
  assert.ok(from2 && 'graph' in from2);
  assert.deepEqual(from2.graph.place.map(x => x.level), [1, 1, 0, 2, 3]);
  assert.deepEqual(from2.graph.edges.length, 4);
});

test('a directed graph follows only outgoing edges; asymmetric undirected pairs show both values (14a)', () => {
  const e = -1;
  const p = projectGraph(matrix([[e, e], [5, e]]), m());
  assert.ok(p && 'graph' in p);
  assert.deepEqual(p.graph.place.map(x => x.level), [0, 1], '1 is not reachable from 0: its own tree');
  assert.deepEqual(p.graph.roots, [0, 1]);
  const u = projectGraph(matrix([[e, 2], [3, e]]), m({ directed: false }));
  assert.ok(u && 'graph' in u);
  assert.equal(u.graph.symmetric, false);
  assert.deepEqual(u.graph.edges.map(x => [x.from, x.to, (x.weight as { value: string }).value, (x.reverse as { value: string }).value]), [[0, 1, '2', '3']]);
});

test('a matrix that stops fitting is reported, never repaired; unknown cells are not facts (14a)', () => {
  const ragged = projectGraph(matrix([[-1, 1], [-1]]), m());
  assert.deepEqual(ragged, { problem: 'not square: row 1 has 1 entries for 2 nodes' });
  const s = matrix([[-1, 1], [-1, -1]]);
  s.objects.set('m', { ...(s.objects.get('m') as object), elements: [{ kind: 'ref', objectId: 'r0' }, { kind: 'null' }] } as never);
  assert.deepEqual(projectGraph(s, m()), { problem: 'row 1 is null' });
  const unknown = projectGraph(matrix([[-1, 'unknown'], [-1, -1]]), m());
  assert.ok(unknown && 'graph' in unknown);
  assert.deepEqual(unknown.graph.edges.map(x => [x.from, x.to, x.unknown, x.tree]), [[0, 1, true, false]]);
  assert.equal(projectGraph({ ...s, bindings: [] }, m()), null, 'not in scope');
});

/** A state where `adj` refers to a list of integer lists (collections) or a jagged int array. */
function lists(rows: number[][], kind: 'collection' | 'array'): State {
  const s = matrix([]);
  const holder = kind === 'collection'
    ? { objectId: 'L', kind: 'collection', className: 'java.util.ArrayList', elements: rows.map((_, i) => ({ kind: 'ref', objectId: `l${i}` })) }
    : { objectId: 'L', kind: 'array', elementType: 'int[]', elements: rows.map((_, i) => ({ kind: 'ref', objectId: `l${i}` })) };
  s.objects.set('L', holder as never);
  rows.forEach((r, i) => s.objects.set(`l${i}`, (kind === 'collection'
    ? { objectId: `l${i}`, kind: 'collection', className: 'java.util.ArrayList', elements: r.map(int) }
    : { objectId: `l${i}`, kind: 'array', elementType: 'int', elements: r.map(int) }) as never));
  s.bindings = [{ ...s.bindings[0], name: 'adj', javaType: 'java.util.List<java.util.List<java.lang.Integer>>', value: { kind: 'ref', objectId: 'L' } }];
  return s;
}

test('neighbour lists: row i lists the neighbours of i, from a list of lists or a jagged array (14b)', () => {
  const rows = [[1, 2], [0, 3], [0], [1]];
  for (const kind of ['collection', 'array'] as const) {
    const u = projectGraph(lists(rows, kind), m({ array: 'adj', form: 'lists', directed: false }));
    assert.ok(u && 'graph' in u, kind);
    assert.equal(u.graph.symmetric, true, 'every edge is listed both ways');
    assert.deepEqual(u.graph.edges.map(e => [e.from, e.to]), [[0, 1], [0, 2], [1, 3]], `${kind}: each pair once`);
    assert.deepEqual(u.graph.place.map(p => p.level), [0, 1, 1, 2]);
    const d = projectGraph(lists(rows, kind), m({ array: 'adj', form: 'lists' }));
    assert.ok(d && 'graph' in d);
    assert.equal(d.graph.edges.length, 6, 'directed: every listed entry');
    assert.equal(d.graph.weighted, false);
  }
  assert.deepEqual(projectGraph(lists([[1], [5]], 'collection'), m({ array: 'adj', form: 'lists' })), { problem: 'row 1 lists 5, which is not a node (0..1)' });
});

test('the step that read or wrote a row lights that edge; reading a row lights its node (14b)', () => {
  const s = lists([[1, 2], [0], [0]], 'collection');
  const p = projectGraph(s, m({ array: 'adj', form: 'lists', directed: false }));
  assert.ok(p && 'graph' in p);
  const event = (kind: string, data: object) => ({ seq: 1, step: 1, frameId: 'f0', source: { start: 0, end: 0 }, kind, data }) as never;
  assert.deepEqual(touched(event('LIBRARY', { objectId: 'L', method: 'java.util.List.get(int)', arguments: [int(2)], result: { kind: 'ref', objectId: 'l2' }, after: null }), p.graph, s),
    { node: 2, access: 'read' });
  assert.deepEqual(touched(event('LIBRARY', { objectId: 'l0', method: 'java.util.Iterator.next()', arguments: [], result: int(2), after: null }), p.graph, s),
    { edge: [0, 2], access: 'read' });
  assert.deepEqual(touched(event('LIBRARY', { objectId: 'l1', method: 'java.util.List.add(java.lang.Object)', arguments: [int(2)], result: { kind: 'boolean', value: true }, after: null }), p.graph, s),
    { edge: [1, 2], access: 'write' });
  const jag = lists([[1, 2], [0], [0]], 'array');
  const q = projectGraph(jag, m({ array: 'adj', form: 'lists' }));
  assert.ok(q && 'graph' in q);
  assert.deepEqual(touched(event('ARRAY_READ', { objectId: 'l0', index: 1, value: int(2) }), q.graph, jag), { edge: [0, 2], access: 'read' });
  assert.deepEqual(touched(null, q.graph, jag), {});
});
test('index variables mark nodes, and linked arrays give one value per node, from this state only (14c)', () => {
  const s = matrix([[-1, 1, -1], [1, -1, 1], [-1, 1, -1]]);
  const p = projectGraph(s, m({ directed: false }));
  assert.ok(p && 'graph' in p);
  const ptr = (name: string, value: number) => ({ name, frameId: 'f0', value, last: 1 });
  // g[u] marks node u; g[u][v] (a row indexed by v) marks node v; out-of-range values mark nothing.
  const marks = markers(p.graph, new Map([['m', [ptr('u', 2)]], ['r0', [ptr('v', 1)]], ['r1', [ptr('v', 1), ptr('w', 9)]]]));
  assert.deepEqual([...marks.entries()].sort(), [[1, ['v']], [2, ['u']]]);
  const l = lists([[1], [0]], 'collection');
  const q = projectGraph(l, m({ array: 'adj', form: 'lists' }));
  assert.ok(q && 'graph' in q);
  assert.deepEqual([...markers(q.graph, new Map([['L', [ptr('u', 1)]], ['l0', [ptr('k', 0)]]])).entries()], [[1, ['u']]], 'a position in a list is not a node');

  s.objects.set('vis', { objectId: 'vis', kind: 'array', elementType: 'boolean', elements: [{ kind: 'boolean', value: true }, { kind: 'boolean', value: false }, { kind: 'unknown' }] } as never);
  s.objects.set('d', { objectId: 'd', kind: 'array', elementType: 'int', elements: [int(0), int(1)] } as never);
  s.bindings.push({ ...s.bindings[0], bindingId: 'b2', name: 'visited', javaType: 'boolean[]', value: { kind: 'ref', objectId: 'vis' } },
    { ...s.bindings[0], bindingId: 'b3', name: 'dist', javaType: 'int[]', value: { kind: 'ref', objectId: 'd' } });
  const values = nodeValues(s, m({ nodeArrays: ['visited', 'dist', 'gone'] }), 3);
  assert.deepEqual(values.map(v => [v.name, v.boolean ?? null, v.note ?? null, v.values?.length ?? null]),
    [['visited', true, null, 3], ['dist', null, 'has 2 entries for 3 nodes', null], ['gone', null, 'not in scope here', null]]);
  // A write to visited[i] lights node i.
  const write = { seq: 1, step: 1, frameId: 'f0', source: { start: 0, end: 0 }, kind: 'ARRAY_WRITE', data: { objectId: 'vis', index: 2, value: { kind: 'boolean', value: true }, previous: { kind: 'boolean', value: false } } } as never;
  assert.deepEqual(touched(write, p.graph, s, ['vis']), { node: 2, access: 'write' });
});