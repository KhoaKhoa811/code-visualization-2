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
