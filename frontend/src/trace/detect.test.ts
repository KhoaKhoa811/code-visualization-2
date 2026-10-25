import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Playback } from './playback.ts';
import { detect, type Detected } from './detect.ts';
import { projectChildren } from './children.ts';
import { projectChains } from './chains.ts';
import { heldBy, reachable } from './reach.ts';
import type { Trace } from './types.ts';

const fixture = (name: string): Trace => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const end = (name: string) => { const p = new Playback(fixture(name)); return { p, found: detect(p, p.steps) }; };
const autos = (found: Detected[]) => found.filter(d => d.auto).map(d => `${d.subject}: ${d.view}`).sort();

test('mixed and nested structures are detected and the unambiguous Strong ones shown automatically (19c, 19f)', () => {
  assert.deepEqual(autos(end('lru').found), ['Main.Node: doubly linked list']);
  assert.deepEqual(autos(end('nary').found), ['Main.Node: tree with children']);
  assert.deepEqual(autos(end('trie').found), ['Main.TrieNode: trie']);
  assert.deepEqual(autos(end('chaining').found), ['Main.Table: hash table with chaining']);
  assert.deepEqual(autos(end('heap-class').found), ['Main.MinHeap: min-heap']);
  assert.deepEqual(autos(end('mixed-structures').found),
    ['Main.Tree: binary tree', 'adj: undirected graph', 'queue: queue', 'stack: stack']);
  // A bubble sort swapping a[0] and a[1] is not a heap (rank-2 needs two pairs, one below the root).
  assert.deepEqual(autos(end('bubble-sort').found), []);
});

test('a next/prev pair is a doubly linked list, not a tree; unlinked nodes are not held against it (19c)', () => {
  const { found } = end('lru');
  const list = found.find(d => d.view === 'doubly linked list')!;
  assert.equal(list.level, 'Strong');
  assert.deepEqual(list.contradictions, [], 'the evicted node keeps stale links but is no longer reachable');
  assert.ok(list.shape?.kind === 'list' && list.shape.mapping.prev === 'prev' && list.shape.mapping.next === 'next');
  assert.ok(!found.some(d => d.subject === 'Main.Node' && d.view === 'binary tree'), 'not offered as a binary tree');
  // A heap class is not offered as a stack once its swaps are parent-child swaps.
  assert.ok(!end('heap-class').found.some(d => d.view === 'array stack' || d.view === 'array queue'));
});

test('a structure is shown from the step it becomes Strong, and stepping back takes it away again (19f)', () => {
  const p = new Playback(fixture('mixed-structures'));
  const treeAt = (k: number) => detect(p, k).find(d => d.subject === 'Main.Tree' && d.view === 'binary tree');
  const first = Array.from({ length: p.steps }, (_, i) => i + 1).find(k => treeAt(k)?.auto)!;
  assert.ok(first > 1);
  assert.ok(!treeAt(first - 1)?.auto, 'not before it is Strong');
  assert.deepEqual(treeAt(first), treeAt(first), 'the same facts give the same result');
  assert.deepEqual(detect(p, first - 1), detect(p, first - 1), 'stepping back gives what arriving there gave');
});

test('children trees, chained slots and who else holds a node (19d, 19e)', () => {
  const trie = new Playback(fixture('trie'));
  const st = trie.at(trie.steps).beforeEnd ?? trie.at(trie.steps).state;
  const tp = projectChildren(st, { className: 'Main.TrieNode', children: 'kids', value: 'end', keyed: true });
  assert.equal(tp.roots.length, 1, 'one root: the others hang under it');
  assert.deepEqual(tp.roots[0].kids.map(k => k.label), ['19'], '"t" is slot 19 of kids');
  assert.equal(tp.nodes, 5);

  const ch = new Playback(fixture('chaining'));
  const sc = ch.at(ch.steps).beforeEnd ?? ch.at(ch.steps).state;
  const [table] = projectChains(sc, { owner: 'Main.Table', array: 'buckets', nodeClass: 'Main.Entry', next: 'next', shown: ['key', 'val'] });
  assert.deepEqual(table.slots.map(s => s.nodes.length), [0, 2, 1, 0], 'slot 1 holds 5 then 1');

  const lru = new Playback(fixture('lru'));
  const sl = lru.at(lru.steps).beforeEnd ?? lru.at(lru.steps).state;
  const live = reachable(sl);
  const held = [...sl.objects.values()].filter(o => o.kind === 'object' && o.className === 'Main.Node' && live.has(o.objectId))
    .flatMap(o => heldBy(sl, o.objectId));
  assert.ok(held.some(h => /^map\[\d+\]$/.test(h)), `map entries name the nodes they hold: ${held.join(', ')}`);
  assert.ok(held.some(h => /\.head$/.test(h)), 'the cache holds its head sentinel');
});