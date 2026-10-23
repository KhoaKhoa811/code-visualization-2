import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { State } from './playback.ts';
import type { Value } from './types.ts';
import { projectGraph, type GraphMapping } from './graph.ts';

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
