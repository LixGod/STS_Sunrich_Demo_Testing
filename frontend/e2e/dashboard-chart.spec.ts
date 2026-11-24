import { expect, test } from '@playwright/test';
import { mockApi } from './mock-api';
import { FINANCE, mockFinanceApi } from './mock-finance-api';

test('Finance sees the lazy receivables chart with the existing precise text summary', async ({ page }) => {
  await mockApi(page, [FINANCE]);
  await mockFinanceApi(page);
  await page.route('**/api/v1/finance/receivables/summary', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ asOf: '2026-10-26', rows: [], totals: [
      { clientName: 'A', currency: 'USD', minorUnits: 2, total: '1000.50',
        buckets: { NOT_DUE: '400.50', D1_30: '600.00', D31_60: 0, D61_90: 0, D90_PLUS: 0 } },
      { clientName: 'B', currency: 'INR', minorUnits: 2, total: '2000.00',
        buckets: { NOT_DUE: '2000.00', D1_30: 0, D31_60: 0, D61_90: 0, D90_PLUS: 0 } },
    ] }),
  }));

  await page.goto('/login');
  await page.getByLabel('Username').fill('finance');
  await page.locator('#password').fill('finance');
  await page.getByRole('button', { name: 'Sign in' }).click();

  const chart = page.locator('.receivables-chart');
  await expect(chart).toBeVisible();
  await chart.scrollIntoViewIfNeeded();
  await expect(chart.locator('canvas')).toBeVisible();
  await expect(page.getByText('1,000.50')).toBeVisible();
  await expect(page.getByText('2,000.00')).toBeVisible();
});
