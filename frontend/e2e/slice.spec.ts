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
