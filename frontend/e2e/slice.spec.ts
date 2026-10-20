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
  await expect(objects).toHaveCSS('order', '2');
  expect(Math.round((await head.boundingBox())!.x - before.x)).toBe(0);
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
