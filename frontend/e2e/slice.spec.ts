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
