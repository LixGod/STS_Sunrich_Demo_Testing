import { Page, expect, test } from '@playwright/test';
import { MARINE, TECHNICAL, mockApi } from './mock-api';
import { mockJobTechnicalApi } from './mock-job-technical-api';

async function signIn(page: Page, username: 'technical' | 'marine') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(username);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

test('Marine uses a vessel GA plan already stored by STS Admin', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  const api = await mockJobTechnicalApi(page, { masterGa: true });
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/technical');
  await expect.poll(() => api.gaLinks).toBe(1);
  // The GA plan on the vessel record ticks the GA row for that vessel and shows the file inline.
  const gaRow = page.locator('.tick-row', { hasText: 'General arrangement plan' });
  await expect(gaRow.locator('input[type=checkbox]').first()).toBeChecked();
  await expect(gaRow).toContainText('mother-ga.pdf');
});

test('Marine can review a received document', async ({ page }) => {
  await mockApi(page, [MARINE]);
  await mockJobTechnicalApi(page, { masterGa: true });
  await signIn(page, 'marine');
  await page.goto('/jobs/job-1/technical');
  const gaRow = page.locator('.tick-row', { hasText: 'General arrangement plan' });
  const review = gaRow.getByRole('button', { name: 'Review General arrangement plan · Mother · DEMO MOTHER' });
  await expect(review).toBeVisible();
  await review.click();
  await expect(review).toHaveCount(0);
  await expect(gaRow.getByText('Checked')).toHaveCount(2);
});

test('job flow keeps received but unreviewed documents and JPO acceptances open', async ({ page }) => {
  await mockApi(page, [MARINE]);
  await mockJobTechnicalApi(page, { receivedButUnreviewed: true });
  await page.route('**/api/v1/jobs/job-1/jpo', route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ revisions: [{ status: 'DONE', acceptanceCount: 2 }] }) }));
  await signIn(page, 'marine');
  await page.goto('/jobs/job-1');
  const documents = page.locator('.steps li', { hasText: 'Documents from owners' });
  const jpo = page.locator('.steps li', { hasText: 'JPO, certificates and weather' });
  await expect(documents).toContainText('4 of 4 received · 1 of 4 pre-arrival reviewed');
  await expect(documents).toContainText('3 pre-arrival reviews pending');
  await expect(documents).not.toHaveClass(/done/);
  await expect(jpo).toContainText('Done · 2 of 3 acceptances');
  await expect(jpo).toContainText('1 JPO acceptance pending');
  await expect(jpo).not.toHaveClass(/done/);
  await expect(page.getByRole('link', { name: 'Finish Marine readiness above' })).toHaveAttribute('href', '/jobs/job-1/technical');
});

test('job document collection and voyage values stay editable on desktop and phones', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [TECHNICAL, MARINE]);
  const api = await mockJobTechnicalApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1');
  await expect(page.getByRole('heading', { name: 'STS flow' })).toBeVisible();
  await expect(page.getByRole('link', { name: /Documents from owners/ })).toHaveAttribute('href', '/jobs/job-1/technical');
  await expect(page.getByRole('link', { name: /JPO, certificates and weather/ })).toHaveAttribute('href', '/jobs/job-1/jpo');
  await expect(page.getByRole('link', { name: /Proposed mooring plan/ })).toHaveAttribute('href', '/jobs/job-1/mooring-plan');
  await page.getByRole('link', { name: /Documents from owners/ }).click();
  await expect(page.getByRole('heading', { name: 'Step 4 · STS Document Collection & Readiness' })).toBeVisible();
  // Mother's GA plan is still missing; the daughter's was checked by a reviewer.
  const gaRow = page.locator('.tick-row', { hasText: 'General arrangement plan' });
  await expect(gaRow.locator('input[type=checkbox]').first()).not.toBeChecked();
  await expect(gaRow.getByText('Checked')).toBeVisible();
  await page.locator('.tick-grid .upload-inline input[type=file]').first().setInputFiles({
    name: 'demo-q88.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\nDEMO\n%%EOF\n'),
  });
  await expect.poll(() => api.uploads).toBe(1);
  await expect(page.getByText('NOT_SCANNED')).toBeVisible();
  expect(api.uploads).toBe(1);
  await page.getByText('Email to owners').click();
  await page.getByRole('button', { name: 'Preview request email' }).click();
  await expect(page.getByText(/Pending placeholders: RADIUS_NM/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Copy email text' })).toBeVisible();

  const first = page.locator('.vessel-card').first();
  await expect(first.getByText('Source: demo-q88.pdf · v1')).toBeVisible();
  await expect(first.getByText('VERIFIED')).toBeVisible();
  await first.locator('.voyage-field input[type=number]').first().fill('7.25');
  await first.getByRole('button', { name: 'Save values' }).click();
  expect(api.saves).toBe(1);
  expect(api.verified).toBe(1);
});

test('checklist templates can be configured and published on a phone-sized screen', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  await mockJobTechnicalApi(page);
  await signIn(page, 'technical');
  await page.goto('/technical/templates');
  await expect(page.getByRole('heading', { name: 'Document templates' })).toBeVisible();
  const checklists = page.getByRole('heading', { name: 'Checklist templates' }).locator('..');
  await checklists.getByRole('textbox', { name: 'Code' }).fill('CHECKLIST-1');
  await checklists.getByRole('textbox', { name: 'Name' }).fill('DEMO signed checklist 1');
  await checklists.getByRole('button', { name: 'Add question' }).click();
  await checklists.getByRole('textbox', { name: 'Code' }).last().fill('DEMO-Q');
  await checklists.getByRole('textbox', { name: 'Question' }).fill('DEMO confirmation');
  await checklists.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByText('Checklist template saved.')).toBeVisible();
  await checklists.getByRole('button', { name: 'Publish checklist' }).click();
  await expect(page.getByText('Checklist template published.')).toBeVisible();
});
