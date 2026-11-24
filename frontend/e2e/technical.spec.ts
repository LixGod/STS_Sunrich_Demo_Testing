import { Locator, Page, expect, test } from '@playwright/test';
import { MARINE, TECHNICAL, mockApi } from './mock-api';
import { SAMPLE_PDF, mockTechnicalApi } from './mock-technical-api';

async function signIn(page: Page, username: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(username);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

async function choose(page: Page, inputId: string, option: string | RegExp) {
  await page.locator(`#${inputId}`).click();
  await page.getByRole('option', { name: option }).last().click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

/** Fills a field and checks it kept the value (retrying while the page settles). */
async function fillIn(field: Locator, value: string) {
  await expect(async () => {
    await field.fill(value);
    await expect(field).toHaveValue(value, { timeout: 500 });
  }).toPass({ timeout: 10_000 });
}

test('particulars are entered beside the source document and verified by a second person', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [TECHNICAL, MARINE]);
  const api = await mockTechnicalApi(page);

  // --- The technical officer uploads the Q88 and enters the particulars ---
  await signIn(page, 'technical');
  await page.goto('/technical/vessels');
  await page.getByRole('link', { name: 'DEMO MOTHER' }).click();
  await expect(page.getByRole('heading', { name: 'DEMO MOTHER' })).toBeVisible();
  await expect(page.locator('s2s-page-header s2s-status-tag')).toContainText('Unverified');

  await choose(page, 'upload-type', 'Q88 questionnaire');
  await page.locator('#upload-file').setInputFiles({ name: 'q88.pdf', mimeType: 'application/pdf', buffer: SAMPLE_PDF });
  await page.getByRole('button', { name: 'Upload' }).click();
  await expect(page.locator('.documents li')).toHaveCount(1);
  await expect(page.locator('.documents')).toContainText('Not scanned');
  expect(api.uploads).toBe(1);

  await page.getByRole('button', { name: 'Start entering particulars' }).click();
  // The uploaded document is shown beside the form, through a short-lived download URL.
  await expect(page.locator('s2s-document-viewer iframe')).toHaveAttribute('src', /\/api\/v1\/files\/download\/tokf-1$/);

  const entries: [string, string, number][] = [['LOA', '183.06', 1], ['BEAM', '32.2', 1], ['BOW_TO_MANIFOLD', '89.17', 4], ['STERN_TO_MANIFOLD', '93.80', 4]];
  for (const [code, value, pageNo] of entries) {
    await fillIn(page.locator(`#v-${code}`), value);
    await choose(page, `s-${code}`, /Q88 questionnaire v1/);
    await fillIn(page.locator(`#pg-${code}`), String(pageNo));
  }
  await page.getByRole('button', { name: /Save$/ }).click();
  // 89.17 + 93.80 = 182.97 ≠ 183.06: a warning, not a block.
  await expect(page.getByText(/but LOA is 183.06 m/)).toBeVisible();
  await fillIn(page.locator('#v-STERN_TO_MANIFOLD'), '93.89');
  await page.getByRole('button', { name: 'Submit for verification' }).click();
  await expect(page.locator('.status-line s2s-status-tag')).toContainText('Submitted');
  await expect(page.getByText(/but LOA is/)).toHaveCount(0);
  expect(api.saves.at(-1)!.values).toEqual(expect.arrayContaining([
    { fieldCode: 'STERN_TO_MANIFOLD', value: '93.89', sourceDocumentId: 'doc-1', sourcePage: 4 },
  ]));
  // The person who entered the values cannot verify them.
  await expect(page.getByRole('button', { name: /Verify$/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();

  // --- The marine superintendent checks every value and verifies ---
  await signIn(page, 'marine');
  await page.goto('/technical/vessels/v-1');
  const verify = page.getByRole('button', { name: /Verify$/ });
  await expect(verify).toBeDisabled();
  for (const [code] of entries) {
    await page.locator(`#c-${code}`).click();
  }
  await expect(verify).toBeEnabled();
  await verify.click();
  await expect(page.locator('.status-line s2s-status-tag')).toContainText('Verified');
  await expect(page.locator('s2s-page-header s2s-status-tag')).toContainText('Verified');
  await expect(page.getByText(/Verified by Demo Marine Superintendent/)).toBeVisible();
  expect(api.verified[0].sort()).toEqual(['BEAM', 'BOW_TO_MANIFOLD', 'LOA', 'STERN_TO_MANIFOLD']);
});
