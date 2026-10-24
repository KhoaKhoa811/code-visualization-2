import { expect, test, type Page } from '@playwright/test';
import { EXAMPLES } from '../src/examples.ts';

const cells = (page: Page, name: string) => page.getByTestId(`array-${name}`).getByTestId('cell').locator('.cell-value');

/** Part 19f: the confirm-by-hand editors sit under "Choose fields yourself" in the Structures card. */
async function openManual(page: Page) {
  const d = page.getByTestId('manual-mappings');
  if (!(await d.evaluate(e => (e as HTMLDetailsElement).open))) await d.locator('summary').click();
}

async function run(page: Page) {
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  await expect(page.getByTestId('run-summary')).toBeVisible();
}

test('bubble sort runs in Docker and plays back step by step', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await run(page);
  const summary = page.getByTestId('run-summary');
  await expect(summary).toContainText('completed');
  await expect(summary).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('1\n2\n3\n4\n5\n');

  const count = page.getByTestId('step-count');
  await expect(count).toHaveText(/^Step 0 \/ \d+$/);
  await expect(page.getByTestId('step-detail')).toContainText('Initial state');
  await expect(page.getByTestId('array-a')).toHaveCount(0); // not declared yet

  const forward = page.getByRole('button', { name: 'Step forward' });
  await forward.click();
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Declare int[] a');
  await expect(cells(page, 'a')).toHaveText(['5', '2', '4', '1', '3']);
  await expect(page.locator('.step-highlight-text').first()).toBeVisible();

  // Step until the first element write and check it is shown as a write.
  for (let i = 0; i < 40; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Write a[')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Write a[0]: 5 → 2');
  await expect(page.getByTestId('array-a').locator('.cell.write')).toHaveCount(1);
  await expect(cells(page, 'a')).toHaveText(['2', '2', '4', '1', '3']); // t still holds 5
  await page.screenshot({ path: 'test-results/slice-write-step.png' });

  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(cells(page, 'a')).toHaveText(['5', '2', '4', '1', '3']); // backward restores, never reruns

  await page.getByTitle('End').click();
  await expect(cells(page, 'a')).toHaveText(['1', '2', '3', '4', '5']);
  await expect(page.getByTestId('step-detail')).toContainText('end of recording');

  // Editing the source invalidates the old playback instead of remapping it.
  await page.locator('.monaco-editor').click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('// edited');
  await expect(summary).toContainText('The source changed after this run');
  await expect(page.getByTestId('step-detail')).toHaveCount(0);
});

test('statements outside coverage run without visualization while the rest is traced (8a)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Helper and library calls');
  await run(page);
  const summary = page.getByTestId('run-summary');
  await expect(summary).toContainText('capture complete');
  await expect(summary).toContainText('some statements ran without capture');
  await expect(page.getByTestId('console-output')).toHaveText('total = 22\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 10; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Ran without visualization')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Ran without visualization (a changed)');
  await expect(page.locator('.step-detail.uncaptured')).toBeVisible();
  await expect(cells(page, 'a')).toHaveText(['4', '2', '1', '1']); // re-read after System.arraycopy (Arrays.sort and, since 12a, Arrays.fill are covered)
  await page.screenshot({ path: 'test-results/partial-coverage.png' });

  await page.getByTitle('End').click();
  // Since 8d the helper square(...) is traced, so total is known; only the printing ran uncaptured.
  await expect(page.getByTestId('var-total')).toContainText('22');
});

test('strings, a map, a stack, a queue and a priority queue play back as library calls (8f)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Strings and collections');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('the appears 2 times\n3 first 1\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 120; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('counts.put(')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('counts.put("the", 1) → null');
  await expect(page.getByTestId('library-counts').getByTestId('entry')).toHaveCount(1);
  await page.screenshot({ path: 'test-results/library-step.png' });

  await page.getByTitle('End').click();
  await expect(page.getByTestId('library-counts').getByTestId('entry')).toHaveText(['"and"→1', '"cat"→1', '"hat"→1', '"the"→2']);
  await expect(page.getByTestId('library-stack').getByTestId('item').locator('.cell-value')).toHaveText(['2', '1']);
  await expect(page.getByTestId('library-queue').getByTestId('item').locator('.cell-value')).toHaveText(['"second"']);
  await expect(page.getByTestId('library-pq').getByTestId('item').locator('.cell-value')).toHaveText(['1', '3', '5']);
  await expect(page.getByTestId('library-word').getByTestId('item')).toHaveCount(0); // setLength(0) after the last word

  // 9d, AC-12: the user chooses to see the ArrayDeque as a stack and the LinkedList as a queue.
  const stack = page.getByTestId('library-stack');
  await stack.getByRole('group', { name: 'stack view' }).getByRole('button', { name: 'Stack' }).click();
  await expect(stack.getByTestId('as-stack').getByTestId('item').locator('.cell-value')).toHaveText(['2', '1']);
  await expect(stack.locator('.cell.top .cell-value')).toHaveText('2'); // push/pop/peek end
  await expect(stack).toContainText('top = first element');
  const queue = page.getByTestId('library-queue');
  await queue.getByRole('group', { name: 'queue view' }).getByRole('button', { name: 'Queue' }).click();
  await expect(queue.getByTestId('as-queue').getByTestId('item')).toHaveText(['"second"front / back']);
  // The choice stays while stepping: back to the first push, then before it the stack is empty.
  for (let i = 0; i < 120; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('stack.push(1)')) break;
    await page.getByRole('button', { name: 'Step back' }).click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('stack.push(1)');
  await expect(stack.getByTestId('as-stack').getByTestId('item').locator('.cell-value')).toHaveText(['1']);
  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(stack).toContainText('empty stack');
  await stack.getByRole('group', { name: 'stack view' }).getByRole('button', { name: 'Deque' }).click();
  await expect(stack.getByTestId('as-stack')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/library-end.png', fullPage: true });
});

test('objects of user classes form a linked list and a tree in the object graph (8e)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Linked list and tree');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('8\n6\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Create Node');
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Call new Node(this = Node');
  for (let i = 0; i < 40; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Write head.next')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Write head.next: null → Node');
  await expect(page.getByTestId('object-head').getByTestId('field-next')).toHaveClass(/write/);

  await page.getByTitle('End').click();
  const graph = page.getByTestId('object-graph');
  await expect(graph.locator('.object')).toHaveCount(7); // 3 nodes + 4 tree nodes
  await expect(page.getByTestId('object-head.next.next').getByTestId('field-value')).toContainText('4'); // the field named value
  await expect(page.getByTestId('object-root.right.left').getByTestId('field-key')).toContainText('6');
  await expect(graph.locator('.graph-arrows path')).toHaveCount(5 + 1); // 2 list links + 3 tree links, plus the marker shape
  await page.screenshot({ path: 'test-results/object-graph.png', fullPage: true });
});

/** Drags an element by its top-left area with the mouse, after scrolling it into view. */
async function dragBy(page: Page, target: ReturnType<Page['locator']>, dx: number, dy: number) {
  await target.scrollIntoViewIfNeeded();
  const box = (await target.boundingBox())!;
  await page.mouse.move(box.x + 12, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + 12 + dx / 2, box.y + 10 + dy / 2, { steps: 4 });
  await page.mouse.move(box.x + 12 + dx, box.y + 10 + dy, { steps: 4 });
  await page.mouse.up();
}

test('dragging diagram boxes and reordering panels changes layout only (9a, AC-18, AC-23, AC-26)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Linked list and tree');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await page.getByTitle('End').click();
  // 19f: the list and the tree are shown automatically; this test is about layout, so they are hidden.
  await page.getByTestId('hide-Main.Node|list').click();
  await page.getByTestId('hide-Main.Tree|tree').click();
  const count = await page.getByTestId('step-count').textContent();

  const head = page.getByTestId('object-head');
  await head.scrollIntoViewIfNeeded(); // the cards above the grid can push it below the fold
  const before = (await head.boundingBox())!;
  const arrows = page.locator('.graph-arrows path:not(marker path)');
  const pathsBefore = await arrows.evaluateAll(paths => paths.map(p => p.getAttribute('d')));
  await dragBy(page, head, 90, 70);
  const after = (await head.boundingBox())!;
  expect(Math.round(after.x - before.x)).toBe(90);
  expect(Math.round(after.y - before.y)).toBe(70);
  await expect(head.getByTestId('field-value')).toContainText('3'); // values unchanged
  await expect(page.getByTestId('step-count')).toHaveText(count!); // cursor unchanged
  const pathsAfter = await arrows.evaluateAll(paths => paths.map(p => p.getAttribute('d')));
  expect(pathsAfter.length).toBe(pathsBefore.length); // same references
  expect(pathsAfter).not.toEqual(pathsBefore); // arrows follow the moved box
  await page.screenshot({ path: 'test-results/layout-dragged.png' });

  await page.getByRole('button', { name: 'Step back' }).click();
  expect(Math.round((await head.boundingBox())!.x - before.x)).toBe(90); // layout survives stepping

  // Panels: drop the Objects title onto the Variables panel; Objects moves first in the grid.
  const objects = page.getByRole('region', { name: 'Objects' });
  await objects.locator('.panel-handle').dragTo(page.getByRole('region', { name: 'Call stack' }));
  await expect(objects).toHaveCSS('order', '0');
  await expect(page.getByRole('region', { name: 'Call stack' })).toHaveCSS('order', '1');

  await page.getByRole('button', { name: 'Reset layout' }).click();
  await expect(objects).toHaveCSS('order', '6'); // after variables, calls (13a), collections, trees, structures, heaps
  expect(Math.round((await head.boundingBox())!.x - before.x)).toBe(0);
});

test('a confirmed node mapping draws a tree sort as a binary search tree (9b, AC-13, AC-27)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Tree sort');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('completed');
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('1 2 5 6 8 \n');

  // 19f: a Strong tree that nothing else competes with is shown automatically, and can be hidden.
  await page.getByTitle('End').click();
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(1);
  await page.getByTestId('hide-Main.Node|tree').click();
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(0);
  // Choosing the fields by hand still works, and a confirmed mapping comes first.
  await openManual(page);
  await expect(page.getByTestId('suggestion-Node')).toContainText('it may be a binary tree node (value key, children left / right)');
  await page.getByTestId('suggestion-Node').getByRole('button', { name: 'Show as tree' }).click();
  await expect(page.getByTestId('mapping-Node')).toContainText('Node shown as a tree');

  const nodes = page.getByTestId('tree-Node').getByTestId('tree-node');
  await expect(nodes).toHaveCount(5);
  const byPosition = async () => (await nodes.evaluateAll(els => els.map(e => {
    const r = e.getBoundingClientRect();
    return { x: r.x, y: r.y, text: e.querySelector('.tree-value')!.textContent };
  })));
  const placed = await byPosition();
  expect([...placed].sort((a, b) => a.x - b.x).map(n => n.text)).toEqual(['1', '2', '5', '6', '8']); // in-order = sorted
  expect([...placed].sort((a, b) => a.y - b.y)[0].text).toBe('5'); // the first key is the root
  await expect(page.getByTestId('tree-Node').locator('.tree-edges line')).toHaveCount(4);
  await expect(page.getByTestId('array-sorted').getByTestId('cell').locator('.cell-value')).toHaveText(['1', '2', '5', '6', '8']);

  // Stepping from the start: the first link written is root.left = 2 under 5, highlighted as a write.
  await page.getByTitle('Restart').click();
  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  for (let i = 0; i < 120; i++) {
    if ((await detail.textContent())?.includes('Write root.left')) break;
    await forward.click();
  }
  await expect(detail).toContainText('Write root.left: null → Node');
  await expect(page.getByTestId('tree-Node').locator('.tree-node.write')).toHaveText(/^5/);
  await expect(nodes).toHaveCount(2);
  await page.screenshot({ path: 'test-results/tree-step.png' });

  // Tree nodes drag like other diagram elements; edges follow.
  await page.getByTitle('End').click();
  const edges = page.getByTestId('tree-Node').locator('.tree-edges line');
  const before = await edges.evaluateAll(ls => ls.map(l => l.getAttribute('x2')));
  await dragBy(page, nodes.filter({ hasText: /^8/ }), 30, 25);
  await expect.poll(async () => (await edges.evaluateAll(ls => ls.map(l => l.getAttribute('x2'))))).not.toEqual(before);
  await expect(edges).toHaveCount(4);

  // The mapping stays confirmed for the next run of the same class.
  await run(page);
  await expect(page.getByTestId('mapping-Node')).toContainText('Node shown as a tree');
  await page.getByTitle('End').click();
  await expect(nodes).toHaveCount(5);
});

test('an array confirmed as a heap shows synchronized array and tree views of heap sort (9c, AC-14, AC-31)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Heap sort');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('1 3 4 5 8 10 \n');

  // Nothing is a heap until the user says so: array, indexing, size variable, order.
  await expect(page.getByRole('region', { name: 'Heaps' })).toHaveCount(0);
  await openManual(page);
  await page.getByRole('button', { name: 'Show an array as a heap…' }).click();
  await page.getByLabel('Heap array').selectOption('a');
  await page.getByLabel('Heap size').selectOption('end');
  await page.getByLabel('Heap bound').selectOption('count');
  await page.getByTestId('heap-editor').getByRole('button', { name: 'Show as heap' }).click();
  await expect(page.getByTestId('heap-mapping-a')).toContainText('a shown as a max-heap (0-based, size from end)');

  // Before a exists there is no heap; before the extraction loop, end is not in scope: no boundary is guessed.
  const heap = page.getByTestId('heap-a');
  await expect(heap).toContainText('a is not in scope at this step');
  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  for (let i = 0; i < 10; i++) {
    if ((await detail.textContent())?.includes('Declare int[] a')) break;
    await forward.click();
  }
  await expect(page.getByTestId('heap-boundary-note')).toContainText('end is not in scope here');

  // First extraction: after the swap, siftDown(a, 0, 5) works on indices 0..4; index 5 holds the maximum.
  for (let i = 0; i < 300; i++) {
    if ((await detail.textContent())?.includes('n = 5')) break;
    await forward.click();
  }
  await expect(detail).toContainText('Call siftDown(a = int[6], i = 0, n = 5)');
  await expect(heap.locator('.heap-strip .cell.outside .cell-value')).toHaveText(['10']);
  await expect(heap.getByTestId('heap-node')).toHaveCount(5);
  await expect(heap.getByTestId('heap-order')).toContainText('does not hold'); // the swapped-in root, observed as recorded
  // The heap is the same array object as the Arrays panel's a.
  const strip = await heap.locator('.heap-strip .cell-value').allTextContents();
  await expect(page.getByTestId('array-a').getByTestId('cell').locator('.cell-value')).toHaveText(strip);
  await page.screenshot({ path: 'test-results/heap-extract.png' });

  // A swap inside siftDown is a write on both projections at the same index.
  for (let i = 0; i < 40; i++) {
    if ((await detail.textContent())?.includes('Write a[')) break;
    await forward.click();
  }
  await expect(heap.locator('.heap-strip .cell.write')).toHaveCount(1);
  await expect(heap.locator('[data-testid="heap-node"].write')).toHaveCount(1);

  await page.getByTitle('End').click();
  await expect(heap.locator('.heap-strip .cell-value')).toHaveText(['1', '3', '4', '5', '8', '10']);
});

test('custom field names map to a list and a circular queue, and a renamed field asks again (10b, AC-27, AC-30)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Custom list and ring buffer');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('30 1 2 3\n');

  // Suggestions from field types, confirmed by hand.
  await openManual(page);
  await expect(page.getByTestId('struct-suggestion-Cell')).toContainText('it may be a linked list node (value item, next after)');
  await expect(page.getByTestId('struct-suggestion-Ring')).toContainText('it may be a stack or a queue');
  await page.getByTestId('struct-suggestion-Cell').getByRole('button', { name: 'Show as list' }).click();
  await page.getByTestId('struct-suggestion-Ring').getByRole('button', { name: 'Show as queue' }).click();
  await expect(page.getByTestId('struct-mapping-Cell')).toContainText('(revision 1, confirmed for this source)');
  await expect(page.getByTestId('struct-mapping-Ring')).toContainText('front head, rear tail (next free slot), size count');

  await page.getByTitle('End').click();
  const nodes = page.getByTestId('list-Cell').getByTestId('list-node');
  await expect(nodes).toHaveCount(3);
  const byX = await nodes.evaluateAll(els => els.map(e => ({ x: e.getBoundingClientRect().x, text: e.querySelector('.tree-value')!.textContent }))
    .sort((a, b) => a.x - b.x).map(n => n.text));
  expect(byX).toEqual(['30', '20', '10']); // first -> ... -> null
  await expect(page.getByTestId('list-Cell').locator('.list-end')).toHaveText('null');
  // The ring buffer wrapped around: slots [5, 2, 3, 4], head 2, tail 1, count 3.
  const ring = page.getByTestId('queue-ring');
  await expect(ring.getByTestId('struct-logical')).toHaveText('front → rear: 3, 4, 5');
  await expect(ring.getByTestId('struct-cell').locator('.cell-index')).toHaveText(['0', '1 rear (next free)', '2 front', '3']);
  await page.screenshot({ path: 'test-results/custom-structures.png', fullPage: true });

  // Rename the link field everywhere: the confirmed list view no longer fits and asks again.
  // Pasted, not typed: typing would add the editor's auto-closed brackets.
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.evaluate(text => navigator.clipboard.writeText(text), EXAMPLES['Custom list and ring buffer'].replaceAll('after', 'link'));
  await page.locator('.monaco-editor').click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Control+V');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('30 1 2 3\n');
  await openManual(page);
  await expect(page.getByTestId('struct-mapping-Cell')).toContainText('does not fit this run: Main.Cell has no field after');
  await expect(page.getByTestId('list-Cell')).toHaveCount(0);
  await page.getByTestId('struct-mapping-Cell').getByRole('button', { name: 'Change' }).click();
  await page.getByLabel('Cell next').selectOption('link');
  await page.getByTestId('struct-editor-Cell').getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByTestId('struct-mapping-Cell')).toContainText('(revision 2, confirmed for this source)');
  await page.getByTitle('End').click();
  await expect(nodes).toHaveCount(3);
});

test('structures are detected from the facts so far, restored on rewind, and shown automatically only when Strong and unambiguous (10c, 19f, AC-32, AC-33)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Tree sort');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  const rankings = page.getByTestId('rankings');
  const tree = page.getByTestId('ranking-Main.Node-binary tree');

  // Step until the first node exists: one node is only Weak evidence for a tree, and nothing is drawn.
  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 40 && (await tree.count()) === 0; i++) await forward.click();
  await expect(tree.locator('.rank-level')).toHaveText('Weak');
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(0);
  const early = await page.getByTestId('step-count').textContent();
  const earlyText = await rankings.textContent();

  await page.getByTitle('End').click();
  await expect(tree.locator('.rank-level')).toHaveText('Strong');
  await expect(tree).toContainText('5 nodes, 4 links');
  await expect(tree).toContainText('observed: search-tree order holds');
  await expect(tree).toContainText('shown automatically');
  await expect(rankings).toContainText('rank-2, steps 1–');
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(1);

  // Back to the same cursor: the same assessment (no later facts leak into it), and the tree is gone again.
  await page.getByTitle('Restart').click();
  while ((await page.getByTestId('step-count').textContent()) !== early) await forward.click();
  await expect(rankings).toHaveText(earlyText!);
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(0);

  // The heap-sort array: parent-child swaps restoring max-heap order make it a max-heap, shown automatically.
  await page.getByLabel('Example').selectOption('Heap sort');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await page.getByTitle('End').click();
  const heap = page.getByTestId('ranking-a-max-heap');
  await expect(heap.locator('.rank-level')).toHaveText('Strong');
  await expect(heap).toContainText('restoring max-heap order');
  await expect(heap).toContainText('observed: max-heap order fails'); // sorted ascending at the end
  await expect(page.getByRole('region', { name: 'Heaps' })).toHaveCount(1);
  await page.screenshot({ path: 'test-results/rankings.png' });
});

test('mixed structures in one program are each detected and drawn: a tree, a graph, a queue and a stack (19)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Mixed structures');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('run-summary')).not.toContainText('without capture');
  await page.getByTitle('End').click();
  await expect(page.getByTestId('ranking-Main.Node-doubly linked list')).toContainText('shown automatically');
  await expect(page.getByTestId('ranking-Main.TrieNode-trie')).toContainText('shown automatically');
  await expect(page.getByTestId('list-Node').getByTestId('list-prev').first()).toBeAttached(); // prev links drawn back
  await expect(page.getByTestId('list-Node').getByTestId('ref-tag').first()).toBeVisible(); // map[k] holds the node
  await expect(page.getByTestId('children-TrieNode').getByTestId('child-node')).toHaveCount(5);
  await expect(page.getByTestId('chains-buckets').getByTestId('chain-node')).toHaveCount(3);
  await page.getByTestId('nested').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/mixed-structures.png', fullPage: true });
});
test('recursive calls get their own frames and returns restore the caller (8d)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Recursion and helpers');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('24\n2\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Call factorial(n = 4)');
  await expect(page.getByTestId('frame')).toHaveCount(2);
  for (let i = 0; i < 60; i++) {
    if ((await page.getByTestId('frame').count()) === 5) break;
    await forward.click();
  }
  await expect(page.getByTestId('frame')).toHaveCount(5); // main + factorial(4..1)
  await expect(page.getByTestId('frame').first()).toContainText('running');
  await expect(page.getByTestId('frame').first().getByTestId('var-n')).toContainText('1');
  await expect(page.getByTestId('frame').nth(3).getByTestId('var-n')).toContainText('4');
  await page.screenshot({ path: 'test-results/recursion.png' });

  await forward.click();
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Return 1');
  await expect(page.getByTestId('frame')).toHaveCount(4);
  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(page.getByTestId('frame')).toHaveCount(5); // backward restores the returned frame

  await page.getByTitle('End').click();
  await expect(page.getByTestId('var-f')).toContainText('24');
  await expect(cells(page, 'a')).toHaveText(['2', '1', '3']);
});

test('a stream pipeline runs its lambdas in frames via the terminal call, and stepping back leaves them (10d, AC-36)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Stream pipeline');
  await run(page);
  const summary = page.getByTestId('run-summary');
  await expect(summary).toContainText('capture complete');
  await expect(summary).not.toContainText('without capture');
  await expect(page.getByTestId('console-output')).toHaveText('22\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 40; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('via sum')) break;
    await forward.click();
  }
  // Lazy: nothing ran while filter and mapToInt were built; sum runs the first filter call.
  await expect(page.getByTestId('step-detail')).toContainText('Call lambda at 10:44 via sum(s = 72)');
  await expect(page.getByTestId('frame')).toHaveCount(2);
  await expect(page.getByTestId('frame').first()).toContainText('via sum');
  await expect(page.getByTestId('frame').first().getByTestId('var-s')).toContainText('72');
  await page.screenshot({ path: 'test-results/stream-lambda.png' });

  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(page.getByTestId('frame')).toHaveCount(1); // before the callback: only main
  await expect(page.getByTestId('step-detail')).toContainText('mapToInt');

  await page.getByTitle('End').click();
  await expect(page.getByTestId('var-total')).toContainText('22');
});

test('steps move: a stored value flies to its cell, a comparison shows its verdict, and motion can be switched off (11a)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Running total');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  const motion = page.getByLabel('Motion');
  await motion.check();
  const forward = page.getByRole('button', { name: 'Step forward' });

  // A comparison pops up its operands and verdict at the code.
  for (let i = 0; i < 10 && !(await page.getByTestId('step-detail').textContent())?.includes('<'); i++) await forward.click();
  await expect(page.locator('.motion-badge')).toContainText(/\d+ < \d+ ✓/);

  // A write carries the value from the highlighted code into the array cell.
  for (let i = 0; i < 20 && !(await page.getByTestId('step-detail').textContent())?.includes('Write values['); i++) await forward.click();
  await expect(page.locator('.motion-chip').first()).toBeVisible();
  await page.screenshot({ path: 'test-results/motion-write.png' });

  // Motion off: steps change state with no chips or badges.
  await motion.uncheck();
  await forward.click();
  await forward.click();
  await expect(page.locator('#motion-layer > *')).toHaveCount(0);
  // No value is left hidden by an interrupted landing.
  const opacities = await page.locator('.bindings .value').evaluateAll(cells => cells.map(c => getComputedStyle(c).opacity));
  expect(opacities.every(o => o === '1')).toBe(true);

  // Speed drives Play: at 4x the cursor advances on its own.
  await page.getByLabel('Speed').selectOption('4');
  const before = Number((await page.getByTestId('step-count').textContent())!.match(/Step (\d+)/)![1]);
  await page.getByRole('button', { name: 'Play' }).click();
  await expect.poll(async () => Number((await page.getByTestId('step-count').textContent())!.match(/Step (\d+)/)![1])).toBeGreaterThan(before + 2);
  await page.getByRole('button', { name: 'Pause' }).click();
});

test('an index variable becomes an arrow under its array once the code indexes with it, and follows its value (11b)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Bubble sort');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  const forward = page.getByRole('button', { name: 'Step forward' });
  const arrow = page.getByTestId('pointer-j');
  await expect(arrow).toHaveCount(0); // nothing indexed yet
  for (let i = 0; i < 40 && (await arrow.count()) === 0; i++) await forward.click();
  await expect(arrow).toBeVisible();

  // The arrow stands under the cell its value names, and moves on when the value changes.
  const under = async () => {
    const value = Number(await arrow.getAttribute('data-value'));
    await page.waitForTimeout(450); // let the slide finish
    const a = (await arrow.boundingBox())!, cell = (await page.getByTestId('array-a').getByTestId('cell').nth(value).boundingBox())!;
    expect(Math.abs((a.x + a.width / 2) - (cell.x + cell.width / 2))).toBeLessThan(8);
    expect(a.y).toBeGreaterThan(cell.y + cell.height - 2);
    return value;
  };
  const start = await under();
  for (let i = 0; i < 40 && Number(await arrow.getAttribute('data-value')) === start; i++) await forward.click();
  expect(await under()).not.toBe(start);
  await page.getByTestId('array-a').screenshot({ path: 'test-results/pointers.png' });
});

test('the chosen example stays selected, and calls and returns move between frames (11c)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  const example = page.getByLabel('Example');
  await example.selectOption('Recursion and helpers');
  await expect(example).toHaveValue('Recursion and helpers'); // the picker shows what was loaded
  for (const name of ['Restart', 'Step back', 'Play', 'Step forward', 'End']) await expect(page.getByRole('button', { name, exact: true }).locator('svg')).toHaveCount(1);
  await run(page);
  await page.getByLabel('Motion').check();
  const forward = page.getByRole('button', { name: 'Step forward' });
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Call factorial(n = 4)');
  await expect(page.locator('.motion-chip').first()).toHaveText('4'); // the argument flies into n
  for (let i = 0; i < 60 && !(await page.getByTestId('step-detail').textContent())?.includes('Return 1'); i++) await forward.click();
  await expect(page.locator('.motion-chip').first()).toHaveText('1'); // the result flies back to the caller
  await page.screenshot({ path: 'test-results/call-return.png' });
});

test('collections move: a pushed value flies in and a popped one flies out to the code (11d)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Strings and collections');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await page.getByLabel('Motion').check();
  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  for (let i = 0; i < 400 && !(await detail.textContent())?.includes('.push('); i++) await forward.click();
  await expect(detail).toContainText('.push(');
  const pushed = (await detail.textContent())!.match(/\.push\((.+)\)/)![1];
  await expect(page.locator('.motion-chip').first()).toHaveText(pushed); // the pushed value flies into the stack
  for (let i = 0; i < 400 && !(await detail.textContent())?.includes('.pop()'); i++) await forward.click();
  await expect(detail).toContainText('.pop()');
  const popped = (await detail.textContent())!.match(/\.pop\(\) → (.+)$/)![1];
  await expect(page.locator('.motion-chip').first()).toHaveText(popped); // the popped value leaves the box
  await page.screenshot({ path: 'test-results/collections-motion.png' });
});

test('long, double and char values are traced exactly (8b)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Number types');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('1307674368000\nA\n0\n');
  await page.getByTitle('End').click();
  await expect(page.getByTestId('var-factorial')).toContainText('1307674368000L');
  await expect(page.getByTestId('var-average')).toContainText('87178291200');
  await expect(page.getByTestId('var-grade')).toContainText("'A'");
  await expect(page.getByTestId('var-low')).toContainText('0');
});

test('a grid, for-each, switch and do-while play back (8c)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Grid and switch');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('18\nmany\nA\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  await forward.click();
  await expect(page.getByTestId('step-detail')).toContainText('Create int[3][]');
  await expect(page.getByTestId('array-grid').getByTestId('row')).toHaveCount(0); // not declared yet: drawn as temporary
  await forward.click();
  await expect(page.getByTestId('array-grid').getByTestId('row')).toHaveCount(3);

  for (let i = 0; i < 400; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Switch on')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Switch on 18: no case matches, default');
  await expect(page.locator('.step-highlight-text').first()).toBeVisible();
  await page.screenshot({ path: 'test-results/grid-switch.png' });

  await page.getByTitle('End').click();
  await expect(page.getByTestId('array-grid').getByTestId('row').nth(2).getByTestId('cell').locator('.cell-value'))
    .toHaveText(['0', '2', '4', '6']);
  await expect(page.getByTestId('var-total')).toContainText('18');
  await expect(page.getByTestId('var-grade')).toContainText("'A'");
  await expect(page.getByTestId('var-countdown')).toContainText('0');
  await expect(page.getByTestId('array-names').getByTestId('cell').locator('.cell-value')).toHaveText(['"zero"', '"one"', '"many"']);

  // 9a, AC-23: an array of arrays moves as one group; its rows and values stay as they are.
  const grid = page.getByTestId('array-grid');
  await grid.scrollIntoViewIfNeeded();
  const before = (await grid.getByTestId('row').nth(2).boundingBox())!;
  await dragBy(page, grid.locator('.array-label'), 60, 40);
  const after = (await grid.getByTestId('row').nth(2).boundingBox())!;
  expect(Math.round(after.x - before.x)).toBe(60);
  expect(Math.round(after.y - before.y)).toBe(40);
  await expect(grid.getByTestId('row').nth(2).getByTestId('cell').locator('.cell-value')).toHaveText(['0', '2', '4', '6']);
});

test('grid index arrows point at the row and at the cell of the row last indexed (12d)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Grid and switch');
  await run(page);
  const grid = page.getByTestId('array-grid');
  const forward = page.getByRole('button', { name: 'Step forward' });
  // Step until grid[r][c] = r * c has run in row 1.
  for (let i = 0; i < 200 && (await grid.getByTestId('row').nth(1).getByTestId('pointer-c').count()) === 0; i++) await forward.click();
  const r = grid.locator('.row-pointer[data-testid="pointer-r"]');
  await expect(r).toHaveAttribute('data-value', '1'); // grid[r]: r points at row 1
  await expect(r).toHaveText('r ▶');
  const c = grid.getByTestId('row').nth(1).getByTestId('pointer-c');
  await expect(c).toHaveText('▲ c'); // grid[r][c]: c points at a cell of row 1 only
  await expect(grid.getByTestId('pointer-c')).toHaveCount(1);
  await expect(c).toHaveAttribute('data-value', (await page.getByTestId('var-c').locator('.value').textContent())!.trim());
  // The row arrow sits beside row 1.
  const arrow = (await r.boundingBox())!, row = (await grid.getByTestId('row').nth(1).boundingBox())!;
  expect(arrow.y + arrow.height / 2).toBeGreaterThan(row.y);
  expect(arrow.y + arrow.height / 2).toBeLessThan(row.y + row.height);
  await page.screenshot({ path: 'test-results/grid-pointers.png' });
});

test('Arrays.fill, an iterator, Math.pow, Stream.of and String.format are all traced (12a-12c)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Iterator and format');
  await run(page);
  const summary = page.getByTestId('run-summary');
  await expect(summary).toContainText('capture complete');
  await expect(summary).not.toContainText('without capture');
  await expect(page.getByTestId('console-output')).toHaveText('4 kept, area 2.25, first a\n');
  const detail = page.getByTestId('step-detail');
  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 200 && !(await detail.textContent())?.includes('remove()'); i++) await forward.click();
  await expect(detail).toContainText('remove()');
  await expect(page.getByTestId('var-it')).toContainText('ArrayList.iterator()');
  await page.getByTitle('End').click();
  await expect(page.getByTestId('array-scores').getByTestId('cell').locator('.cell-value')).toHaveText(['10', '10', '3', '10', '10']);
});

test('a returning frame leaves the call stack while motion is on (12d)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Recursion and helpers');
  await run(page);
  const detail = page.getByTestId('step-detail');
  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 400 && !(await detail.textContent())?.includes('Return'); i++) await forward.click();
  await expect(detail).toContainText('Return');
  const ghost = page.getByTestId('frame-leaving');
  await expect(ghost).toHaveCount(1);
  await expect(ghost).toContainText('returned');
  await forward.click();
  if (!(await detail.textContent())?.includes('Return')) await expect(ghost).toHaveCount(0); // gone on the next step
  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(ghost).toHaveCount(0); // stepping back brings the frame back; nothing leaves
});

test('the call tree keeps every call with its result, and a caller shows what it waits for and received (13)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Fibonacci (call tree)');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('3\n');
  const tree = page.getByTestId('call-tree');
  const forward = page.getByRole('button', { name: 'Step forward' });
  // Step until fib(n - 1) of the outer call has returned and fib(n - 2) is running: the caller
  // fib(4) shows the received value and the call it now waits for on the same line.
  const outer = page.getByTestId('frame').filter({ has: page.locator('.frame-name', { hasText: /^fib/ }) }).last();
  for (let i = 0; i < 300; i++) {
    if (await outer.getByTestId('call-filled').count() && await outer.getByTestId('call-waiting').count()) break;
    await forward.click();
  }
  await expect(outer.getByTestId('call-filled')).toContainText('fib(n - 1) = 2'); // fib(3) returned 2
  await expect(outer.getByTestId('call-waiting')).toContainText('fib(n - 2)');
  await expect(outer.getByTestId('call-link')).toHaveText('waits at return fib(n - 1) = 2 + fib(n - 2) ⏳ fib(2);');
  await expect(tree.locator('[data-status="current"]')).toHaveCount(1);
  await expect(tree.locator('.call-label', { hasText: 'fib(3) → 2' })).toHaveCount(1);
  await page.screenshot({ path: 'test-results/call-tree.png' });

  // The next steps add the + : the received values stay until fib(4) uses them.
  await page.getByTitle('End').click();
  await expect(tree.locator('.call-label', { hasText: 'fib(4) → 3' })).toHaveCount(1);
  await expect(tree.getByTestId('call-node')).toHaveCount(10); // main and nine calls of fib
  await expect(tree.locator('[data-status="returned"]')).toHaveCount(9);
  // Rewinding removes calls that have not happened yet.
  await page.getByTitle('Restart').click();
  await expect(tree).toHaveCount(0);
});

test('a matrix confirmed as a graph is drawn by levels, follows writes and lights the edge a step reads (14a)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Graph BFS (matrix)');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('[0, 1, 2, 3, 4]\n');
  await page.getByTitle('End').click();
  await openManual(page);
  await page.getByRole('button', { name: 'Show a variable as a graph…' }).click();
  await expect(page.getByLabel('Graph variable')).toHaveValue('g');
  await expect(page.getByLabel('Graph form')).toHaveValue('matrix');
  await expect(page.getByLabel('Graph edges')).toHaveValue('undirected'); // suggested: symmetric at this step
  await expect(page.getByTestId('graph-hint')).toContainText('5 nodes, 6 edges; symmetric');
  await page.getByRole('button', { name: 'Show as graph' }).click();

  const graph = page.getByTestId('graph-g');
  await expect(graph.getByTestId('graph-node')).toHaveCount(5);
  await expect(graph.getByTestId('graph-edge')).toHaveCount(6);
  await expect(graph.locator('[data-testid="graph-edge"][data-tree="true"]')).toHaveCount(4); // the level tree from node 0
  await expect(graph.locator('[data-node="0"]')).toHaveAttribute('data-level', '0');
  await expect(graph.locator('[data-node="4"]')).toHaveAttribute('data-level', '3');
  await expect(graph.locator('.graph-weight').first()).toBeVisible(); // weights other than 1 are drawn
  await graph.scrollIntoViewIfNeeded();
  await graph.screenshot({ path: 'test-results/graph-matrix.png' });

  // Back in the BFS: the step reading g[u][v] lights that edge (or no edge is lit when the cell is -1).
  const detail = page.getByTestId('step-detail');
  const back = page.getByRole('button', { name: 'Step back' });
  for (let i = 0; i < 200 && (await graph.locator('.graph-edge.lit').count()) === 0; i++) await back.click();
  await expect(graph.locator('.graph-edge.lit')).toHaveCount(1);
  await expect(detail).toContainText('Read g[4][3] = 3'); // the last edge BFS reads
  const lit = graph.locator('.graph-edge.lit');
  expect([await lit.getAttribute('data-from'), await lit.getAttribute('data-to')].sort()).toEqual(['3', '4']);

  // Before the edges are written there are fewer edges; before g exists the view says so.
  await page.getByTitle('Restart').click();
  await expect(graph).toContainText('not in scope');
});

test('neighbour lists confirmed as a graph: a DFS lights each edge it walks (14b)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Graph DFS (lists)');
  await run(page);
  const summary = page.getByTestId('run-summary');
  await expect(summary).toContainText('capture complete');
  await expect(summary).not.toContainText('without capture');
  await expect(page.getByTestId('console-output')).toHaveText('[0, 1, 3, 2, 4, 5]\n');
  await page.getByTitle('End').click();
  await openManual(page);
  await page.getByRole('button', { name: 'Show a variable as a graph…' }).click();
  await page.getByLabel('Graph variable').selectOption('adj');
  await expect(page.getByLabel('Graph form')).toHaveValue('lists');
  await expect(page.getByLabel('Graph edges')).toHaveValue('undirected'); // every edge is listed both ways
  await expect(page.getByLabel('Graph no edge')).toHaveCount(0); // only for a matrix
  await page.getByRole('button', { name: 'Show as graph' }).click();

  const graph = page.getByTestId('graph-adj');
  await expect(graph.getByTestId('graph-node')).toHaveCount(6);
  await expect(graph.getByTestId('graph-edge')).toHaveCount(6);
  await expect(graph.locator('[data-testid="graph-edge"][data-tree="true"]')).toHaveCount(5);
  await expect(graph.locator('[data-node="5"]')).toHaveAttribute('data-level', '4');
  await graph.scrollIntoViewIfNeeded();
  await graph.screenshot({ path: 'test-results/graph-lists.png' });

  // Step back into the DFS: the for-each's next() over adj.get(u) lights edge u - v.
  const back = page.getByRole('button', { name: 'Step back' });
  for (let i = 0; i < 300 && (await graph.locator('.graph-edge.lit').count()) === 0; i++) await back.click();
  await expect(graph.locator('.graph-edge.lit')).toHaveCount(1);
  // Before the lists are filled the graph has fewer edges, never edges from later steps.
  await page.getByTitle('Restart').click();
  await expect(graph).toContainText('not in scope');
});

test('a linked visited array colours the nodes and the index variables mark them (14c)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Graph BFS (matrix)');
  await run(page);
  await page.getByTitle('End').click();
  await openManual(page);
  await page.getByRole('button', { name: 'Show a variable as a graph…' }).click();
  await page.getByLabel('Graph node array visited').check();
  await page.getByRole('button', { name: 'Show as graph' }).click();
  const graph = page.getByTestId('graph-g');
  await expect(graph.getByTestId('graph-legend')).toContainText('visited[i] is true');
  await expect(graph.locator('.graph-node.flagged')).toHaveCount(5); // every node was visited

  // Back inside the BFS loop: u (g[u]) and v (g[u][v]) mark their nodes; fewer nodes are visited
  // at the first read, and a write to visited[v] lights node v.
  const back = page.getByRole('button', { name: 'Step back' });
  for (let i = 0; i < 300 && (await graph.getByTestId('graph-marker').count()) < 2; i++) await back.click();
  await expect(graph.getByTestId('graph-marker').filter({ hasText: 'u' })).toHaveCount(1);
  await expect(graph.getByTestId('graph-marker').filter({ hasText: 'v' })).toHaveCount(1);
  await page.getByTitle('Restart').click();
  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  for (let i = 0; i < 400 && !(await detail.textContent())?.includes('Write visited[1]: false → true'); i++) await forward.click();
  await expect(detail).toContainText('Write visited[1]: false → true');
  await expect(graph.locator('.graph-node.lit')).toHaveAttribute('data-node', '1');
  await expect(graph.locator('.graph-node.flagged')).toHaveCount(2); // 0, then 1: only what is recorded so far
  await graph.scrollIntoViewIfNeeded();
  await graph.screenshot({ path: 'test-results/graph-traversal.png' });
});

test('each method gets an estimated complexity, ranked next to what the run measured (15)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Compare algorithms');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('4 4 8\n');
  // Badges after each method's line in the editor.
  await expect(page.locator('.complexity-badge').first()).toBeVisible();
  const badges = await page.locator('.complexity-badge').allTextContents();
  expect(badges.map(b => b.replace(/\s+/g, ' ').trim())).toEqual(expect.arrayContaining(['time O(n)', 'time ≈ O(log n)', 'time O(n²)', 'time ≈ O(n log n)'])); // lines in view

  const table = page.getByTestId('complexity');
  await table.scrollIntoViewIfNeeded();
  const order = await table.getByTestId('complexity-row').evaluateAll(rows => rows.map(r => r.getAttribute('data-method')));
  expect(order).toEqual(['binarySearch', 'linearSearch', 'mergeSort', 'bubbleSort', 'fib', 'main']); // main calls fib
  const row = (name: string) => table.locator(`[data-method="${name}"]`);
  await expect(row('fib').getByTestId('complexity-bound')).toHaveText('O(2ⁿ)');
  await expect(row('fib').getByTestId('complexity-calls')).toHaveText('25'); // fib(6) makes 25 calls
  await expect(row('mergeSort').getByTestId('complexity-calls')).toHaveText('11');
  await row('mergeSort').getByTestId('complexity-bound').click(); // the reasons open
  await expect(row('mergeSort')).toContainText('on half the input');
  await table.screenshot({ path: 'test-results/complexity.png' });
});

test('the template has the imports and main ready, and code typed into it runs traced (16)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  const example = page.getByLabel('Example');
  await expect(example.locator('option').nth(1)).toHaveText('New program (template)'); // first after "Load example…"
  await example.selectOption('New program (template)');
  await expect(page.locator('.monaco-editor')).toContainText(/import\s+java\.util\.\*;/); // Monaco renders spaces as non-breaking spaces
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('run-summary')).not.toContainText('without capture');
});

test('a DP write shows the cells it was computed from; heat, bars, ranges and string arrows (18a-18c, 18e)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('DP and two pointers');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('35 2 6 true\n');
  const detail = page.getByTestId('step-detail');
  const forward = page.getByRole('button', { name: 'Step forward' });
  // 18a: paths[1][1] = paths[0][1] + paths[1][0]: two arrows into the written cell.
  for (let i = 0; i < 300 && !(await detail.textContent())?.includes('Write paths[1][1]'); i++) await forward.click();
  const grid = page.getByTestId('array-paths');
  await expect(page.getByTestId('action-overlay')).toHaveAttribute('data-count', '2'); // 21-22: drawn by the shared overlay
  await grid.scrollIntoViewIfNeeded();
  await grid.screenshot({ path: 'test-results/dp-flow.png' });
  await grid.getByTestId('look-heat').click();
  await expect(grid.getByTestId('look-heat')).toHaveAttribute('aria-pressed', 'true');
  await page.getByTitle('End').click();
  await expect(grid.getByTestId('cell').last()).toHaveText(/35/);
  await grid.screenshot({ path: 'test-results/dp-heat.png' });

  // 18b: bars; 18c: left and right shade the cells between them.
  const a = page.getByTestId('array-a');
  await a.getByTestId('look-bars').click();
  await expect(a.getByTestId('bar')).toHaveCount(7);
  await page.getByTitle('Restart').click();
  for (let i = 0; i < 600 && !(await detail.textContent())?.includes('Read a[6]'); i++) await forward.click();
  await expect(a.locator('.cell.in-range')).toHaveCount(7); // left = 0, right = 6
  // 18e: s.charAt(i) and s.charAt(j) put i and j over the characters.
  for (let i = 0; i < 600 && !(await detail.textContent())?.includes('.charAt(6)'); i++) await forward.click();
  const s = page.getByTestId('string-s');
  await expect(s.getByTestId('char')).toHaveCount(7);
  await expect(s.getByTestId('pointer-i')).toHaveAttribute('data-value', '0');
  await expect(s.getByTestId('pointer-j')).toHaveAttribute('data-value', '6');
  await a.scrollIntoViewIfNeeded();
  await a.screenshot({ path: 'test-results/bars-range.png' });
  await s.scrollIntoViewIfNeeded();
  await s.screenshot({ path: 'test-results/string-pointers.png' });
});

test('a variable referring to a node is a tag on it (18d)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Linked list and tree');
  await run(page);
  await page.getByTitle('End').click();
  await expect(page.getByTestId('var-tag-head').first()).toBeVisible();
});

test('a DP table shows every cell: computed or not yet, where it came from, and its reuses (20)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('DP: 0/1 knapsack');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('35\n');
  const table = page.getByTestId('dp-best');
  const now = table.getByTestId('dp-now');
  const forward = page.getByRole('button', { name: 'Step forward' });
  // Step to best[2][4] = max(best[2][4], best[1][1] + value[1]): it is computed from cells of the table.
  // The table appears once the program has filled cells from cells (it is not a DP table at step 0).
  const said = async (l: typeof now) => (await l.count()) ? (await l.textContent()) ?? '' : '';
  for (let i = 0; i < 400 && !(await said(now)).includes('stores 35'); i++) await forward.click();
  await expect(now).toContainText('stores 35 in best[2][4] from weight[1] = 3, best[1][1] = 15, value[1] = 20');
  await expect(table.locator('.dp-cell.now-write')).toHaveCount(1);
  await expect(table.locator('.dp-cell.now-source')).toHaveCount(1); // best[1][1] (best[2][4] itself is not an arrow)
  await expect(table.locator('[data-state="empty"]').first()).toBeVisible(); // later rows are not computed yet
  await table.scrollIntoViewIfNeeded();
  await table.screenshot({ path: 'test-results/dp-knapsack.png' });

  // At the end: click a cell to see what it came from and which cells used it.
  await page.getByTitle('End').click();
  await expect(table.locator('[data-state="empty"]')).toHaveCount(5); // row 0 keeps Java's default 0: a base row never computed
  await table.locator('[data-dp-cell]').nth(5 + 1).click(); // best[1][1]
  await expect(table.getByTestId('dp-detail')).toContainText('best[1][1] = 15: computed at step');
  await expect(table.getByTestId('dp-detail')).toContainText('used by');
  await expect(table.getByTestId('dp-arrows')).not.toHaveAttribute('data-count', '0');

  // Top-down: the memo's -1 cells are not computed; a stored value is reused instead of recomputed.
  await page.getByLabel('Example').selectOption('DP: Fibonacci with a memo');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('13\n');
  const memo = page.getByTestId('dp-memo');
  const memoNow = memo.getByTestId('dp-now');
  for (let i = 0; i < 400 && !(await said(memoNow)).includes('reuses memo['); i++) await forward.click();
  await expect(memoNow).toContainText('stored at step');
  await expect(memo.locator('.dp-cell.now-reuse')).toHaveCount(1);
  await page.getByTitle('End').click();
  await expect(memo.getByTestId('dp-reuses').first()).toBeVisible();
  await expect(memo.locator('[data-state="initial"]')).toHaveCount(2); // memo[0] and memo[1] keep their -1 fill
  await memo.scrollIntoViewIfNeeded();
  await memo.screenshot({ path: 'test-results/dp-memo.png' });
});

test('flow arrows: a swap is a double arrow between the two cells, and each write points back to what it read (21)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Bubble sort');
  await run(page);
  const overlay = page.getByTestId('action-overlay');
  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  // int t = a[j]: an arrow from the cell to the variable.
  for (let i = 0; i < 200 && !(await detail.textContent())?.includes('Declare int t'); i++) await forward.click();
  await expect(overlay).toHaveAttribute('data-count', '1');
  // ... a[j + 1] = t completes the swap: a double arrow between the two cells, and t into the cell.
  const swap = page.getByTestId('action-swap');
  for (let i = 0; i < 20 && (await swap.count()) === 0; i++) await forward.click();
  await expect(swap).toHaveCount(1);
  await expect(swap.locator('.action-label')).toHaveText('swap');
  await expect(page.getByTestId('action-ghost')).toHaveCount(1); // the value the write replaced, crossed out
  await page.screenshot({ path: 'test-results/flow-swap.png' });
  // 22: the next comparison of two cells is a bracket between them with its verdict, not a value arrow.
  for (let i = 0; i < 30 && !(await overlay.getAttribute('data-kinds'))?.includes('compare'); i++) await forward.click();
  await expect(detail).toContainText(' > '); // a[j] > a[j + 1]; a loop condition like j < n gets no bracket
  await expect(page.locator('[data-testid="action-yes"], [data-testid="action-no"]')).toHaveCount(1);
  await expect(page.getByTestId('action-value')).toHaveCount(0);
});

test('actions: links rewired, a pointer moving, push and pop, code jumps, and the Arrows switch (22)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Mixed structures');
  await run(page);
  const forward = page.getByRole('button', { name: 'Step forward' });
  const overlay = page.getByTestId('action-overlay');
  // An LRU link change: the old link crossed out in red, the new one in green.
  for (let i = 0; i < 300 && !(await overlay.getAttribute('data-kinds'))?.includes('rewire'); i++) await forward.click();
  await expect(page.getByTestId('action-new').first()).toBeAttached();
  await page.screenshot({ path: 'test-results/action-rewire.png' });

  // Bubble sort: the inner loop goes round again: a jump arrow back up in the code margin.
  await page.getByLabel('Example').selectOption('Bubble sort');
  await run(page);
  const jump = page.getByTestId('code-jump');
  for (let i = 0; i < 60 && !((await jump.count()) && (await jump.getAttribute('data-kind')) === 'back'); i++) await forward.click();
  await expect(jump).toHaveAttribute('data-kind', 'back');
  // Arrows off: no actions and no jump.
  await page.getByLabel('Arrows').uncheck();
  await expect(page.getByTestId('code-jump')).toHaveCount(0);
  await expect(overlay).toHaveAttribute('data-count', '0');
  await page.getByLabel('Arrows').check();

  // A queue: offer goes in, poll comes out (Graph BFS keeps its queue in a deque).
  await page.getByLabel('Example').selectOption('Graph BFS (matrix)');
  await run(page);
  for (let i = 0; i < 400 && !(await overlay.getAttribute('data-kinds'))?.includes('stack'); i++) await forward.click();
  await expect(page.getByTestId('action-stack').locator('.action-label')).toHaveText(/^(add|offer) \d+$/);
});

test('a program reads live console input and playback shows the read (8g, AC-19)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Read input');
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  // The prompt streams before any input exists.
  await expect(page.getByTestId('console-live')).toHaveText('Enter n: ');
  const input = page.getByLabel('Console input');
  await expect(input).toBeEnabled();
  // 6g: the kernel shows the blocked Scanner read, so the console says it waits (polled about once a second).
  await expect(page.getByTestId('console-waiting')).toContainText('Waiting for input', { timeout: 15_000 });
  await page.screenshot({ path: 'test-results/live-console-prompt.png' });
  await input.fill('21');
  await input.press('Enter');
  await expect(page.getByTestId('run-summary')).toBeVisible();
  await expect(page.getByTestId('run-summary')).toContainText('completed');
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('Enter n: 42\n');
  await expect(page.getByTestId('console-input')).toContainText('"21"');

  // Replay shows the read from the recording; it never sends input again.
  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 20; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Read input')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Read input: in.nextInt() → 21');
  await page.screenshot({ path: 'test-results/live-console-read.png' });
  await page.getByTitle('End').click();
  await expect(page.getByTestId('var-n')).toContainText('21');
});

test('test input given with the run replaces the live console and stays tied to its run (10a, AC-35)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Read input');
  await page.getByLabel('Use test input').check();
  await page.getByLabel('Test input', { exact: true }).fill('21\n');
  await run(page);
  // No console input was typed: the program read the given text, then end of input.
  await expect(page.getByTestId('run-summary')).toContainText('completed');
  await expect(page.getByTestId('console-output')).toHaveText('Enter n: 42\n');
  await expect(page.getByTestId('run-input')).toContainText('Input: test input, 3 bytes (SHA-256 ');
  await expect(page.getByLabel('Console input')).toHaveCount(0);

  // Replay reads the recorded input; it never sends it again.
  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 20; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Read input')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Read input: in.nextInt() → 21');

  // Editing the test input afterwards keeps this playback tied to the input it was given.
  await page.getByLabel('Test input', { exact: true }).fill('5\n');
  await expect(page.getByTestId('input-changed')).toContainText('This playback used the input it was given');
  await expect(page.getByTestId('step-detail')).toContainText('in.nextInt() → 21');
  await run(page);
  await expect(page.getByTestId('console-output')).toHaveText('Enter n: 10\n');
  await expect(page.getByTestId('input-changed')).toHaveCount(0);

  // Too little input: Java's own end-of-input failure, recorded like any other.
  await page.getByLabel('Test input', { exact: true }).fill('');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('failed');
  await expect(page.getByTestId('run-summary')).toContainText('NoSuchElementException');
});

test('a helper throws, its frame unwinds, and main catches with finally on every path (8h)', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Exceptions');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('completed');
  await expect(page.getByTestId('run-summary')).toContainText('capture complete');
  await expect(page.getByTestId('console-output')).toHaveText('balance 70\nrefused: short by 430\nbalance 70\nbalance 50\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  const detail = page.getByTestId('step-detail');
  for (let i = 0; i < 80; i++) {
    if ((await detail.textContent())?.includes('Throw')) break;
    await forward.click();
  }
  await expect(detail).toContainText('Throw InsufficientFunds: short by 430');
  await expect(page.getByTestId('frame')).toHaveCount(2); // main and withdraw at the throw
  await forward.click();
  await expect(detail).toContainText('Catch e = InsufficientFunds: short by 430');
  await expect(page.getByTestId('frame')).toHaveCount(1); // withdraw unwound
  await expect(page.getByTestId('var-e')).toContainText('InsufficientFunds("short by 430")');
  // 8i: after the long throw line the editor scrolls back so the catch line shows from its start.
  await expect(page.locator('.editor .lines-content')).toHaveCSS('left', '0px');
  await page.screenshot({ path: 'test-results/exception-catch.png' });
  await forward.click();
  await expect(detail).toContainText('e.getMessage() → "short by 430"');

  await page.getByRole('button', { name: 'Step back' }).click();
  await page.getByRole('button', { name: 'Step back' }).click();
  await expect(page.getByTestId('frame')).toHaveCount(2); // backward restores the unwound frame
});

test('another program and a compile error', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.monaco-editor')).toBeVisible();
  await page.getByLabel('Example').selectOption('Running total');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('completed');
  await expect(page.getByTestId('console-output')).toHaveText('14\ntrue\n');
  await page.getByTitle('End').click();
  await expect(cells(page, 'values')).toHaveText(['0', '1', '4', '9']);
  await expect(page.getByTestId('var-total')).toContainText('14');

  await page.locator('.monaco-editor').click();
  await page.keyboard.press('Control+A');
  await page.keyboard.type('public class Main { int x = ; ');
  await run(page);
  await expect(page.getByTestId('run-summary')).toContainText('compile_error');
  await expect(page.getByTestId('step-count')).toHaveText('Step 0 / 0');
  await expect(page.locator('.squiggly-error').first()).toBeVisible();
});
