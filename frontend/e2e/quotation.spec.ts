import { Locator, Page, expect, test } from '@playwright/test';
import { COMMERCIAL, mockApi } from './mock-api';
import { mockCommercialApi } from './mock-commercial-api';
import { mockQuotationApi } from './mock-quotation-api';

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible({ timeout: 15_000 });
}

/** Fills a field in a dialog that may still be moving focus as it opens (WebKit), retrying until the value sticks. */
async function fillIn(field: Locator, value: string) {
  await expect(async () => {
    await field.fill(value);
    await expect(field).toHaveValue(value, { timeout: 500 });
  }).toPass({ timeout: 10_000 });
}

async function choose(page: Page, inputId: string, option: string | RegExp) {
  await page.locator(`#${inputId}`).click();
  await page.getByRole('option', { name: option }).last().click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

test('estimate → quotation → issue → owner acceptance creates the job', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  const api = await mockQuotationApi(page);

  // --- Commercial prepares the estimate ---
  await signIn(page, 'commercial', 'commercial');
  await page.goto('/commercial/enquiries/enq-9/estimate');
  await expect(page.getByText('No estimate has been prepared for this enquiry yet.')).toBeVisible();
  await page.getByRole('button', { name: 'Start estimate' }).click();

  // Picking inventory equipment fills the cost head, name and rate; only the quantity is typed.
  await page.getByRole('button', { name: 'Add cost line' }).click();
  await choose(page, 'equip-0', /Synthetic fender 3\.3 m \(Primary fender\) · 4 available/);
  await expect(page.locator('#head-0')).toHaveCount(0); // the cost head comes from the equipment category, so its box is hidden
  await expect(page.locator('#price-0')).toHaveValue('1,250.125');
  await page.locator('#qty-0').fill('2');
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-1', 'STS-SERVICE — STS service charge');
  await page.locator('#price-1').fill('10000');
  await page.locator('#price-1').blur();

  // Live totals are exact (big.js): 2 x 1250.125 = 2,500.25; margin 7,499.75 = 75.00 %.
  const totals = page.locator('.totals');
  await expect(totals).toContainText('USD 2,500.25');
  await expect(totals).toContainText('USD 10,000.00');
  await expect(totals).toContainText('USD 7,499.75 (75.00%)');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.p-toast-message')).toContainText('Changes saved');
  expect(api.estimateSaves[0].lines).toEqual([
    expect.objectContaining({ kind: 'COST', quantity: '2', unitPrice: '1250.125', costHeadId: 'h-1', equipmentModelId: 'm-1', description: 'Synthetic fender 3.3 m' }),
    expect.objectContaining({ kind: 'REVENUE', quantity: '1', unitPrice: '10000' }),
  ]);

  // --- Commercial prepares, issues, and records the owner's acceptance ---
  await page.getByRole('link', { name: 'Quotation' }).click();
  await expect(page).toHaveURL(/\/enquiries\/enq-9\/quotation$/);
  await page.getByRole('button', { name: 'Prepare quotation' }).click();
  const terms = page.getByRole('dialog');
  // The calendar can re-align and close while the dialog is still opening; retry until the date sticks.
  await expect(async () => {
    await terms.locator('#q-valid').click();
    await page.locator('.p-datepicker-panel td:not(.p-datepicker-other-month) .p-datepicker-day', { hasText: /^28$/ }).click({ timeout: 2_000 });
    await page.locator('.p-datepicker-panel').getByRole('button', { name: 'Apply' }).click({ timeout: 2_000 });
    await expect(terms.locator('#q-valid')).toHaveValue(/^\d{4}-\d{2}-28 \d{2}:\d{2}$/, { timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
  await expect(terms.locator('#q-tariff')).toContainText('DEMO-STS');
  await choose(page, 'q-tariff', /DEMO-STS/);
  await terms.getByText('More details').click();
  await fillIn(terms.locator('#q-payment'), 'Synthetic: 30 days');
  await terms.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const detail = page.locator('article.detail');
  await expect(detail).toContainText('USD 10,000.00');
  await expect(detail).toContainText('48 h base: USD 10,000.00');
  await expect(page.getByRole('link', { name: 'Open PDF' })).toHaveAttribute('href', /\/api\/v1\/quotation-versions\/qv-1\/pdf$/);

  await detail.getByRole('button', { name: 'Mark as issued' }).click();
  await fillIn(page.getByRole('dialog').locator('#i-to'), 'chartering@demo.invalid');
  await page.getByRole('dialog').getByRole('button', { name: 'Mark as issued' }).click();
  await expect(page.locator('article.detail s2s-status-tag').first()).toContainText('Issued');
  await expect(page.getByRole('link', { name: 'Open PDF' })).toHaveAttribute('href', /\/api\/v1\/quotation-versions\/qv-1\/pdf$/);

  await page.locator('article.detail').getByRole('button', { name: 'Record owner acceptance' }).click();
  const accept = page.getByRole('dialog');
  await expect(accept).toContainText('This creates the STS job');
  await fillIn(accept.locator('#a-by'), 'Demo Charterer Representative');
  await fillIn(accept.locator('#a-job'), 'JOB/2026/00001');
  await accept.getByRole('button', { name: 'Record owner acceptance' }).click();
  await expect(page.locator('.p-toast-message').first()).toContainText('Job JOB/2026/00001 was created.');
  expect(api.acceptances).toHaveLength(1);
  expect(api.acceptances[0].key).toBeTruthy(); // Idempotency-Key: a retry cannot create a second job

  // --- The job shows what was accepted, with the tariff frozen ---
  await page.getByRole('button', { name: 'Open job JOB/2026/00001' }).click();
  await expect(page).toHaveURL(/\/jobs\/job-1$/);
  await expect(page.getByRole('heading', { name: 'JOB/2026/00001' })).toBeVisible();
  await expect(page.getByText('Frozen tariff')).toBeVisible();
  await expect(page.locator('dd', { hasText: 'USD 10,000.00' }).first()).toBeVisible();
  await expect(page.getByText('Pending confirmation').first()).toBeVisible();
});

test('Commercial can price extra tariff hours in the PDA', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  const api = await mockQuotationApi(page);
  await signIn(page, 'commercial', 'commercial');
  await page.goto('/commercial/enquiries/enq-9/estimate');
  await page.getByRole('button', { name: 'Start estimate' }).click();
  await choose(page, 'estimate-tariff', /DEMO-STS/);
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-0', 'STS-SERVICE — STS service charge');
  await page.locator('#price-0').fill('10000');
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-1', /ADD-TARIFF — Additional tariff/);
  await page.getByRole('checkbox', { name: /Extra hours/ }).check();
  await page.locator('#qty-1').fill('3');
  await page.locator('#qty-1').blur();
  await expect(page.locator('#price-1')).toHaveValue('250.00');
  await expect(page.locator('.totals')).toContainText('USD 10,750.00');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.p-toast-message')).toContainText('Changes saved');
  expect(api.estimateSaves[0].lines).toEqual(expect.arrayContaining([
    expect.objectContaining({ extraHours: true, quantity: '3', unitPrice: '250' }),
  ]));
});

test('PDA saves complete lines and omits incomplete rows without required line fields', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  const api = await mockQuotationApi(page);
  await signIn(page, 'commercial', 'commercial');
  await page.goto('/commercial/enquiries/enq-9/estimate');
  await page.getByRole('button', { name: 'Start estimate' }).click();

  await page.getByRole('button', { name: 'Add cost line' }).click();
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-1', 'STS-SERVICE — STS service charge');
  await page.locator('#price-1').fill('1000');
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-2', 'STS-SERVICE — STS service charge');
  await page.locator('#qty-2').clear();
  await page.locator('#price-2').fill('200');
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-3', 'STS-SERVICE — STS service charge');
  await expect(page.locator('s2s-form-field', { has: page.locator('#head-0') })).not.toContainText('*');
  await expect(page.locator('s2s-form-field', { has: page.locator('#qty-2') })).not.toContainText('*');
  await expect(page.locator('s2s-form-field', { has: page.locator('#price-3') })).not.toContainText('*');
  await expect(page.locator('.totals')).toContainText('USD 1,000.00');

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.p-toast-message')).toContainText('Changes saved');
  expect(api.estimateSaves[0].lines).toEqual([
    expect.objectContaining({ kind: 'REVENUE', quantity: '1', unitPrice: '1000' }),
  ]);
  await page.locator('#price-0').clear();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.estimateSaves.length).toBe(2);
  expect(api.estimateSaves[1].lines).toEqual([]);
});

test('PDA keeps a saved zero-price deferred tariff line on a later save', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  const api = await mockQuotationApi(page);
  await signIn(page, 'commercial', 'commercial');
  await page.goto('/commercial/enquiries/enq-9/estimate');
  await page.getByRole('button', { name: 'Start estimate' }).click();
  await page.getByRole('button', { name: 'Add revenue line' }).click();
  await choose(page, 'head-0', /ADD-TARIFF — Additional tariff/);
  await page.locator('#price-0').fill('0');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.estimateSaves.length).toBe(1);
  await expect(page.locator('#price-0')).toHaveValue('0.00');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.estimateSaves.length).toBe(2);
  expect(api.estimateSaves[1].lines).toEqual([
    expect.objectContaining({ quantity: '0', unitPrice: '0' }),
  ]);
});
