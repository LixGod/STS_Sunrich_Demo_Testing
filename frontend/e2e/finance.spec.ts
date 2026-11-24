import { Locator, Page, expect, test } from '@playwright/test';
import { mockApi } from './mock-api';
import { FINANCE, FIN_APPROVER, OPERATOR, mockFinanceApi, mockJobFinanceApi } from './mock-finance-api';

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
}

async function fillIn(field: Locator, value: string) {
  await expect(async () => {
    await field.fill(value);
    await expect(field).toHaveValue(value, { timeout: 500 });
  }).toPass({ timeout: 10_000 });
}

async function choose(page: Page, select: Locator, option: string) {
  await select.click();
  await page.getByRole('option', { name: option }).click();
}

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
}

test('an invoice is drafted from the quotation, approved by someone else, issued and paid by a receipt, on desktop and phones', async ({ page }) => {
  test.setTimeout(120_000);
  await mockApi(page, [OPERATOR, FINANCE, FIN_APPROVER]);
  await mockJobFinanceApi(page); // first, so the invoice mocks below take precedence
  const api = await mockFinanceApi(page);

  // Operations drafts the invoice from the accepted quotation and adds a supplementary extra-hours line.
  // The old job invoices link opens the Invoices tab of the job's finance page.
  await signIn(page, 'operations', 'operations');
  await page.goto('/jobs/job-1/invoices');
  await expect(page).toHaveURL(/\/jobs\/job-1\/finance\?tab=invoices$/);
  await page.getByRole('button', { name: 'New invoice from the accepted quotation' }).click();
  await expect(page).toHaveURL(/\/finance\/invoices\/inv-1$/);
  await expect(page.getByText('From the quotation')).toBeVisible();
  await expect(page.getByTestId('invoice-gross')).toHaveText('USD 10,000.00');
  await expect(page.locator('.blockers')).toContainText('Enter the due date.');
  await noHorizontalScroll(page);

  await page.getByRole('button', { name: 'Add line' }).click();
  await choose(page, page.locator('#ln-head-1'), 'STS-SERVICE — STS service charge');
  await fillIn(page.locator('#ln-desc-1'), 'Extra hours (synthetic)');
  await fillIn(page.locator('#ln-qty-1'), '2.5');
  await fillIn(page.locator('#ln-price-1'), '250.333');
  // Live totals, exact: 10,000 + 625.83.
  await expect(page.getByTestId('invoice-gross')).toHaveText('USD 10,625.83');
  await fillIn(page.locator('#inv-due'), '2026-11-17');
  await expect(page.getByText('Save your changes before submitting.')).toBeVisible();
  await expect(page.locator('.blockers')).toHaveCount(0);
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.saves.length).toBe(1);
  expect(api.saves[0]).toMatchObject({
    dueDate: '2026-11-17',
    lines: [
      { source: 'QUOTATION', costHeadCode: 'STS-SERVICE', quantity: '1', unitPrice: '10000' },
      { source: 'MANUAL', costHeadCode: 'STS-SERVICE', description: 'Extra hours (synthetic)', quantity: '2.5', unitPrice: '250.333' },
    ],
  });
  await expect(page.locator('.blockers')).toHaveCount(0);
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.locator('.head s2s-status-tag').first()).toContainText('Submitted');
  // The submitter may not approve their own invoice.
  await expect(page.locator('.actions').getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, 'approver', 'approver');
  await page.goto('/finance/invoices/inv-1');
  await expect(page.locator('.actions').getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, 'finance', 'finance');
  await page.goto('/finance/invoices/inv-1');
  await page.locator('.actions').getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('.head s2s-status-tag').first()).toContainText('Approved');
  expect(api.decisions).toEqual([{ decision: 'APPROVE', reason: null }]);
  await signOut(page);

  // Issuing assigns the number from the series.
  await signIn(page, 'operations', 'operations');
  await page.goto('/finance/invoices/inv-1');
  await page.locator('.actions').getByRole('button', { name: 'Issue' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Issue' }).click();
  await expect(page.getByRole('heading', { name: 'INV/2026/00001' })).toBeVisible();
  await expect(page.getByTestId('invoice-open')).toHaveText('USD 10,625.83');
  await expect(page.getByRole('link', { name: 'Issued PDF' })).toHaveAttribute('href', /\/api\/v1\/finance\/invoices\/inv-1\/pdf$/);
  await expect(page.locator('#inv-name')).toBeDisabled();

  // A receipt for more than the invoice: the invoice is paid in full and the rest stays on account.
  await signOut(page);
  await signIn(page, 'finance', 'finance');
  await page.goto('/finance/invoices/inv-1');
  await page.getByRole('button', { name: 'Record receipt' }).click();
  await expect(page).toHaveURL(/\/finance\/receipts\?invoice=inv-1$/);
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('#rc-amount')).toHaveValue('10625.83');
  await expect(dialog.getByLabel('Amount for INV/2026/00001')).toHaveValue('10625.83');
  await fillIn(dialog.locator('#rc-amount'), '13000');
  await fillIn(dialog.locator('#rc-ref'), 'SYNTH-REF-1');
  await expect(dialog.getByTestId('receipt-summary')).toContainText('On account 2,374.17');
  await noHorizontalScroll(page);
  await dialog.getByRole('button', { name: 'Record receipt' }).click();
  await expect.poll(() => api.receipts.length).toBe(1);
  expect(api.receipts[0]).toMatchObject({ clientId: 'cl-1', currency: 'USD', amount: '13000', method: 'BANK_TRANSFER',
    reference: 'SYNTH-REF-1', allocations: [{ invoiceId: 'inv-1', amount: '10625.83' }] });
  const receipt = page.locator('.receipt').first();
  await expect(receipt).toContainText('USD 13,000.00');
  await expect(receipt).toContainText('On account 2,374.17');
  await noHorizontalScroll(page);

  await page.goto('/finance/receivables');
  await expect(page.getByText('Nothing is open on this day.')).toBeVisible();

  // Reversing needs a reason; the invoice is open again.
  await page.goto('/finance/receipts');
  await page.locator('.receipt').first().getByRole('button', { name: 'Reverse' }).click();
  await page.locator('#reason-input').fill('Synthetic: posted to the wrong client');
  await page.getByRole('dialog').getByRole('button', { name: 'Reverse' }).click();
  await expect(page.locator('.receipt').first()).toContainText('Reversed: Synthetic: posted to the wrong client');
  await page.goto('/finance/receivables');
  await expect(page.locator('.items')).toContainText('INV/2026/00001');
  await expect(page.locator('.total-card')).toContainText('USD 10,625.83');
  await expect(page.getByRole('link', { name: 'Export to Excel' })).toHaveAttribute('href', /\/api\/v1\/finance\/receivables\/xlsx\?asOf=\d{4}-\d{2}-\d{2}$/);
  await noHorizontalScroll(page);
});

test('the billing menu lists invoices, receipts and receivables for operations users', async ({ page }) => {
  await mockApi(page, [OPERATOR, FINANCE, FIN_APPROVER]);
  await mockFinanceApi(page);
  await signIn(page, 'operations', 'operations');
  await page.goto('/finance');
  await expect(page).toHaveURL(/\/finance\/invoices$/);
  await expect(page.getByText('No invoices match.')).toBeVisible();
  await noHorizontalScroll(page);
});

test('a purchase order is raised, approved by someone else and numbered, and shows as a commitment, on desktop and phones', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [OPERATOR, FINANCE, FIN_APPROVER]);
  const api = await mockJobFinanceApi(page);

  await signIn(page, 'operations', 'operations');
  await page.goto('/jobs/job-1/finance');
  const tabs = page.getByRole('navigation', { name: 'Finance steps' });
  await expect(tabs.getByRole('link')).toHaveText(['Estimate', 'Extra hours', 'Invoices', 'Actual costs', 'FDA']);
  await expect(tabs.getByRole('link', { name: 'Estimate' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('heading', { name: /Budget versus actual/ })).toBeVisible();
  await expect(page.getByText('USD 150.00')).toBeVisible();
  await page.getByText('Cost head breakdown').click();
  await expect(page.locator('table')).toContainText('TUGS · Tugboats');
  await noHorizontalScroll(page);

  // Extra hours: the clock passed the 48 included hours; the tariff-priced charge is invoiced from here.
  await tabs.getByRole('link', { name: 'Extra hours' }).click();
  await expect(page).toHaveURL(/tab=extraHours$/);
  await expect(page.locator('ul.warnings[role="alert"]')).toContainText('passed the 48 included hours');
  await expect(page.getByRole('status')).toContainText('Awaiting approval');
  await expect(page.locator('.summary-figures')).toContainText('52.5');
  await expect(page.locator('.summary-figures')).toContainText('USD 1250');
  await page.locator('select[name="extraCostHead"]').selectOption('STS-SERVICE');
  await noHorizontalScroll(page);
  await page.getByRole('button', { name: 'Invoice finalized extra hours' }).click();
  await expect(page).toHaveURL(/\/finance\/invoices\/inv-9$/);
  expect(api.extraHours).toEqual([{ costHeadCode: 'STS-SERVICE' }]);

  await page.goto('/jobs/job-1/finance?tab=fda');
  await expect(page.getByText('Live figures from issued client invoices and approved vendor bills.')).toBeVisible();
  await expect(page.locator('.summary-figures')).toContainText('USD 250.00');
  await expect(page.locator('.summary-figures')).toContainText('Approved vendor bills');

  await page.goto('/jobs/job-1/finance?tab=expenses');
  await page.getByRole('button', { name: 'New cost document' }).click();
  await page.locator('select[name="kind"]').selectOption('PO');
  await page.locator('select[name="vendor"]').selectOption('v-1');
  await fillIn(page.locator('input[name="reference"]'), 'PO-REQ-1');
  const firstLine = page.locator('form.editor .cost-table tbody tr').first();
  await firstLine.locator('select').first().selectOption('TUGS');
  await expect(firstLine.locator('input').nth(0)).toHaveValue('Tugboats');
  await fillIn(firstLine.locator('input').nth(0), 'Synthetic tug hire');
  await fillIn(firstLine.locator('input').nth(2), '100');
  await noHorizontalScroll(page);
  await page.locator('form.editor').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.costs.length).toBe(1);
  expect(api.costs[0]).toMatchObject({ kind: 'PO', vendorId: 'v-1', reference: 'PO-REQ-1', currency: 'USD',
    lines: [{ costHeadCode: 'TUGS', description: 'Synthetic tug hire', quantity: '1', unitPrice: 100 }] });
  await page.locator('article').first().getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.locator('article').first()).toContainText('Submitted');
  await signOut(page);

  await signIn(page, 'finance', 'finance');
  await page.goto('/jobs/job-1/finance');
  await expect(tabs.getByRole('link', { name: 'Actual costs' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('button', { name: 'New cost document' })).toHaveCount(0);
  await expect(page.locator('article').first().getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, 'approver', 'approver');
  await page.goto('/jobs/job-1/finance?tab=expenses');
  await page.locator('article').first().getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('article').first()).toContainText('PO/2026/00001');
  await noHorizontalScroll(page);
  await page.getByRole('link', { name: 'Estimate' }).click();
  await expect(page.locator('table')).toContainText('100.00');
  await page.getByRole('link', { name: 'Actual costs' }).click();

  // Closing needs a reason and the approve permission.
  await page.locator('article').first().getByRole('button', { name: 'Close purchase order with reason' }).click();
  const reasonDialog = page.getByRole('dialog', { name: 'Reason for return or reversal' });
  await reasonDialog.getByLabel('Reason').fill('Synthetic: no more work expected');
  await reasonDialog.getByRole('button', { name: 'Confirm' }).click();
  await expect(page.locator('article').first()).toContainText('Closed');
  expect(api.actions.at(-1)).toEqual({ decision: 'CLOSE', reason: 'Synthetic: no more work expected' });
});

test('cost preview sums rounded lines', async ({ page }) => {
  await mockApi(page, [OPERATOR]);
  await mockJobFinanceApi(page);
  await signIn(page, 'operations', 'operations');
  await page.goto('/jobs/job-1/finance?tab=expenses');
  await page.getByRole('button', { name: 'New cost document' }).click();
  await page.locator('form.editor .cost-table tbody tr').first().locator('input').nth(2).fill('0.005');
  await page.getByRole('button', { name: 'Add cost line' }).click();
  await page.locator('form.editor .cost-table tbody tr').nth(1).locator('input').nth(2).fill('0.005');
  await expect(page.locator('.cost-summary-bar .grand')).toContainText('USD 0.02');
});

test('purchase orders, vendor bills and profitability are listed across jobs', async ({ page }) => {
  await mockApi(page, [FINANCE, FIN_APPROVER]);
  await mockJobFinanceApi(page);
  await signIn(page, 'finance', 'finance');
  await page.goto('/finance/profitability');
  await expect(page.getByRole('heading', { name: 'Profitability' })).toBeVisible();
  await expect(page.locator('.items')).toContainText('JOB/2026/00001');
  await expect(page.locator('.items')).toContainText('USD 7,500.00');
  await expect(page.locator('.items')).toContainText('75%');
  await expect(page.getByRole('link', { name: 'Export to Excel' })).toHaveAttribute('href', /\/api\/v1\/finance\/profitability\/xlsx/);
  await noHorizontalScroll(page);
  await page.goto('/finance/purchase-orders');
  await expect(page.getByRole('heading', { name: 'Purchase orders' })).toBeVisible();
  await expect(page.getByText('Nothing matches.')).toBeVisible();
  await page.goto('/finance/vendor-bills');
  await expect(page.getByRole('heading', { name: 'Vendor bills' })).toBeVisible();
  await noHorizontalScroll(page);
});

test('Operations enters a vendor bill and Finance approves it', async ({ page }) => {
  await mockApi(page, [OPERATOR, FINANCE, FIN_APPROVER]);
  const api = await mockJobFinanceApi(page);
  await signIn(page, 'operations', 'operations');
  await page.goto('/jobs/job-1/finance?tab=expenses');
  await page.getByRole('button', { name: 'New cost document' }).click();
  await page.locator('select[name="vendor"]').selectOption('v-1');
  await fillIn(page.locator('input[name="reference"]'), 'BILL-1');
  const line = page.locator('form.editor .cost-table tbody tr').first();
  await line.locator('select').first().selectOption('TUGS');
  await fillIn(line.locator('input').nth(2), '100');
  await page.locator('input[type="file"]').setInputFiles({
    name: 'vendor.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\nDEMO\n%%EOF\n'),
  });
  await page.locator('form.editor').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.costs.length).toBe(1);
  expect(api.costs[0]['kind']).toBe('BILL');
  await page.locator('article').first().getByRole('button', { name: 'Submit for approval' }).click();
  await signOut(page);

  await signIn(page, 'finance', 'finance');
  await page.goto('/jobs/job-1/finance?tab=expenses');
  await page.locator('article').first().getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('article').first()).toContainText('Approved');
  expect(api.actions.at(-1)).toEqual({ decision: 'APPROVE', reason: '' });
});
