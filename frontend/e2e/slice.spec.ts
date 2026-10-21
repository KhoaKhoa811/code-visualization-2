import { expect, test, type Page } from '@playwright/test';

const cells = (page: Page, name: string) => page.getByTestId(`array-${name}`).getByTestId('cell').locator('.cell-value');

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
  await expect(page.getByTestId('console-output')).toHaveText('total = 30\n');

  const forward = page.getByRole('button', { name: 'Step forward' });
  for (let i = 0; i < 10; i++) {
    if ((await page.getByTestId('step-detail').textContent())?.includes('Ran without visualization')) break;
    await forward.click();
  }
  await expect(page.getByTestId('step-detail')).toContainText('Ran without visualization (a changed)');
  await expect(page.locator('.step-detail.uncaptured')).toBeVisible();
  await expect(cells(page, 'a')).toHaveText(['1', '2', '3', '4']); // re-read after Arrays.sort
  await page.screenshot({ path: 'test-results/partial-coverage.png' });

  await page.getByTitle('End').click();
  // Since 8d the helper square(...) is traced, so total is known; only the printing ran uncaptured.
  await expect(page.getByTestId('var-total')).toContainText('30');
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

/** Drags an element by its top-left area with the mouse. */
async function dragBy(page: Page, target: ReturnType<Page['locator']>, dx: number, dy: number) {
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
  const count = await page.getByTestId('step-count').textContent();

  const head = page.getByTestId('object-head');
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
  await expect(objects).toHaveCSS('order', '4');
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

  // Nothing is drawn as a tree until the mapping is confirmed.
  await expect(page.getByTestId('suggestion-Node')).toContainText('it may be a binary tree node (value key, children left / right)');
  await page.getByTitle('End').click();
  await expect(page.getByRole('region', { name: 'Trees' })).toHaveCount(0);
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
  const before = (await grid.getByTestId('row').nth(2).boundingBox())!;
  await dragBy(page, grid.locator('.array-label'), 60, 40);
  const after = (await grid.getByTestId('row').nth(2).boundingBox())!;
  expect(Math.round(after.x - before.x)).toBe(60);
  expect(Math.round(after.y - before.y)).toBe(40);
  await expect(grid.getByTestId('row').nth(2).getByTestId('cell').locator('.cell-value')).toHaveText(['0', '2', '4', '6']);
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
