import { test } from 'node:test';
import assert from 'node:assert/strict';
import { forNewRun, initialLayout, movePanel, panelOrder, setMode, setOffset, toggleClosed } from './layout.ts';

test('panels reorder by moving one to another one\'s place (9a)', () => {
  const start = initialLayout();
  assert.deepEqual(start.order, ['variables', 'calls', 'collections', 'trees', 'structures', 'heaps', 'objects', 'arrays', 'strings', 'graphs', 'complexity', 'nested', 'dp']);
  const moved = movePanel(start, 'arrays', 'variables');
  assert.deepEqual(moved.order, ['arrays', 'variables', 'calls', 'collections', 'trees', 'structures', 'heaps', 'objects', 'strings', 'graphs', 'complexity', 'nested', 'dp']);
  assert.deepEqual(movePanel(moved, 'arrays', 'strings').order, ['variables', 'calls', 'collections', 'trees', 'structures', 'heaps', 'objects', 'strings', 'arrays', 'graphs', 'complexity', 'nested', 'dp']);
  assert.equal(movePanel(start, 'arrays', 'arrays'), start, 'dropping on itself changes nothing');
  assert.equal(movePanel(start, 'unknown', 'arrays'), start);
  assert.equal(panelOrder(moved, 'arrays'), 0);
  assert.equal(panelOrder(moved, 'not-a-panel'), 13, 'unknown panels go last');
});

test('element offsets are layout only and end with the run (9a)', () => {
  const dragged = setOffset(initialLayout(), 'object:o3', 40, -12);
  assert.deepEqual(dragged.offsets['object:o3'], { x: 40, y: -12 });
  assert.equal(setOffset(dragged, 'object:o3', 40, -12), dragged, 'an unchanged offset keeps the same layout');
  const reordered = movePanel(dragged, 'strings', 'variables');
  const next = forNewRun(reordered);
  assert.deepEqual(next.offsets, {}, 'ids of an earlier run are never reused');
  assert.deepEqual(next.order, reordered.order, 'panel order is a preference that stays');
});

test('a deque view choice belongs to a variable and survives runs (9d)', () => {
  const chosen = setMode(setOffset(initialLayout(), 'collection:o4', 5, 5), 'stack', 'stack');
  assert.deepEqual(chosen.modes, { stack: 'stack' });
  const next = forNewRun(chosen);
  assert.deepEqual(next.modes, { stack: 'stack' }, 'names, unlike ids, mean the same in the next run');
  assert.deepEqual(next.offsets, {});
  assert.deepEqual(setMode(next, 'stack', null).modes, {}, 'Deque restores the class view');
  assert.deepEqual(initialLayout().modes, {}, 'Reset layout clears choices');
});

test('a panel closes and opens again; closed panels stay closed for a new run (24)', () => {
  const start = initialLayout();
  assert.deepEqual(start.closed, []);
  const closed = toggleClosed(toggleClosed(start, 'objects'), 'calls');
  assert.deepEqual(closed.closed, ['objects', 'calls']);
  assert.deepEqual(toggleClosed(closed, 'objects').closed, ['calls']);
  assert.deepEqual(forNewRun(setOffset(closed, 'object:o2', 5, 5)).closed, ['objects', 'calls']);
  assert.deepEqual(initialLayout().closed, [], 'Reset layout opens every panel');
});