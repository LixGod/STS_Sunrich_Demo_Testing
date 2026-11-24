import { Locator, Page, expect, test } from '@playwright/test';
import { TECHNICAL, mockApi } from './mock-api';
import { mockJpoApi } from './mock-jpo-api';

async function signIn(page: Page, username: 'technical') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(username);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

/** Fills a field that may still be moving focus (dialogs opening, WebKit), retrying until the value sticks. */
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

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
}

test('allocated hoses appear in the JPO without another quantity field', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  await mockJpoApi(page, { linkedEquipment: true });
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/jpo');
  await page.getByRole('button', { name: 'Prepare the JPO' }).click();
  await expect(page.getByText('HOSE-01')).toBeVisible();
  await expect(page.locator('#eq-n-0')).toHaveCount(0);
  await expect(page.locator('#eq-n-2')).toBeVisible();
});

test('a JPO is prepared, marked done with its final PDF and accepted, on desktop and phones', async ({ page }) => {
  test.setTimeout(120_000);
  await mockApi(page, [TECHNICAL]);
  const api = await mockJpoApi(page);

  // --- The technical officer prepares the plan ---
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1');
  await page.getByRole('link', { name: /JPO, certificates and weather/ }).click();
  await expect(page).toHaveURL(/\/jobs\/job-1\/jpo$/);
  await page.getByRole('button', { name: 'Prepare the JPO' }).click();

  const detail = page.locator('article.detail');
  const blockers = page.locator('.blockers');
  await detail.locator('.job-facts summary').click();
  await expect(blockers).toContainText('Enter the date of the operation.');
  await expect(blockers).toContainText('Enter the cargo hoses supplied.');
  await expect(detail.getByText('From job data', { exact: true })).toBeVisible();
  await expect(detail).toContainText('DEMO MOTHER');
  await expect(detail).toContainText('9.85 m');
  await expect(detail.getByText('Entered on the JPO').first()).toBeVisible();
  await expect(detail.getByRole('button', { name: 'Mark done and create PDF' })).toBeDisabled();
  await noHorizontalScroll(page);

  // The calendar can re-align while opening; retry until the picked day sticks.
  await expect(async () => {
    await page.locator('#jpo-date').click();
    await page.locator('.p-datepicker-panel td:not(.p-datepicker-other-month) .p-datepicker-day', { hasText: /^28$/ }).click({ timeout: 2_000 });
    await expect(page.locator('#jpo-date')).toHaveValue(/^\d{4}-\d{2}-28$/, { timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
  await choose(page, 'jpo-mooringDiagramDocumentId', /demo-mooring\.png/);
  await choose(page, 'jpo-shipHandling', 'Helm orders');
  await fillIn(page.locator('#jpo-contingency'), 'Synthetic contingency plan');
  await page.getByRole('button', { name: 'Add a limit' }).click();
  await page.locator('#lim-p-0').fill('Wind');
  await page.locator('#lim-l-0').fill('25');
  await page.locator('#lim-u-0').fill('kn');
  await page.locator('#lim-a-0').fill('Stop and review');
  await page.locator('#eq-n-0').pressSequentially('2');
  await page.locator('#eq-n-0').blur();
  await expect(detail.getByText('Save your changes before marking the plan done.')).toBeVisible();
  await detail.getByRole('button', { name: 'Save' }).click();

  await expect.poll(() => api.saves.length).toBe(1);
  expect(api.saves[0]).toMatchObject({
    operationDate: expect.stringMatching(/^\d{4}-\d{2}-28$/), mooringDiagramDocumentId: 'doc-2', shipHandling: 'HELM_ORDERS', contingency: 'Synthetic contingency plan',
    operatingLimits: [{ parameter: 'Wind', limit: '25', unit: 'kn', action: 'Stop and review' }],
  });
  expect((api.saves[0]['equipment'] as Record<string, unknown>[])[0]).toMatchObject({ category: 'CARGO_HOSE', number: 2 });
  await expect(blockers).toHaveCount(0);

  // Saving the completed plan marks it done: no approver, no annex publishing; the final PDF is ready.
  await expect(detail.getByRole('button', { name: 'Mark done and create PDF' })).toHaveCount(0);
  await expect(detail.locator('.detail-head s2s-status-tag')).toContainText('Done');
  await expect(detail.getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await expect(page.locator('#jpo-contingency')).toBeDisabled();
  await expect(detail.getByRole('link', { name: 'Final PDF' })).toHaveAttribute('href', /\/api\/v1\/jobs\/job-1\/jpo\/revisions\/rev-1\/pdf$/);
  await expect(detail.getByRole('link', { name: 'Excel' })).toHaveAttribute('href', /\/revisions\/rev-1\/xlsx$/);
  await expect(detail).toContainText('a'.repeat(64));

  await detail.getByRole('button', { name: 'Record acceptance' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('#acc-party')).toContainText('Master, discharging vessel');
  await fillIn(dialog.locator('#acc-name'), 'Demo Master A');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(detail).toContainText('Demo Master A');
  expect(api.acceptances[0]).toMatchObject({ party: 'DISCHARGING_MASTER', personName: 'Demo Master A', signedDocumentId: null });
  await noHorizontalScroll(page);
});

test('annex wording is pasted and saved, with no publish step', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [TECHNICAL]);
  const api = await mockJpoApi(page);

  await signIn(page, 'technical');
  await page.goto('/technical/annex');
  await expect(page.getByRole('heading', { name: 'JPO annex wording' })).toBeVisible();
  await expect(page.getByText('Wording still to be entered.').first()).toBeVisible();
  await fillIn(page.locator('#an-body-0'), 'Synthetic annex wording for RISK.');
  await fillIn(page.locator('#an-body-1'), 'Synthetic annex wording for REVERSE.');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.annexSaves.length).toBe(1);
  expect((api.annexSaves[0]['sections'] as Record<string, unknown>[]).map((s) => s['body']))
    .toEqual(['Synthetic annex wording for RISK.', 'Synthetic annex wording for REVERSE.']);
  await expect(page.getByRole('button', { name: 'Publish' })).toHaveCount(0);
  await noHorizontalScroll(page);
});
