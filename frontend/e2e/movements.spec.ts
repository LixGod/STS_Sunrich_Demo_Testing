import { expect, Page, test } from '@playwright/test';
import { ADMIN, APPROVER, OPS, mockApi } from './mock-api';
import { mockMovementsApi } from './mock-movements-api';

async function signIn(page: Page, username = 'ops.mumbai', password = 'ops') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

test('management can decide a trip correction requested by operations', async ({ page }) => {
  await mockApi(page, [ADMIN, OPS, { ...APPROVER, permissions: [...APPROVER.permissions,
    'OPS.MOVEMENT.READ', 'OPS.MOVEMENT.CHANGE.APPROVE'] }]);
  const api = await mockMovementsApi(page);
  api.trips.push({ id: 'trip-1', jobId: 'job-1', craftId: null, craftIdentifier: null, vendorId: null, vendorName: null,
    departedAt: '2026-10-19T01:30:00Z', returnedAt: '2026-10-21T01:00:00Z', timeZone: 'Asia/Kolkata', assets: [],
    serviceNotes: null, standbyNotes: null, delayNotes: null, damageNotes: null, status: 'ACTIVE', files: [],
    pendingAction: 'CORRECT', pendingJson: '{}', pendingReason: 'Corrected paper log', pendingBy: 'ops.manager', version: 1 });
  await signIn(page, 'approver', 'approver');
  await page.goto('/jobs/job-1/movements');
  const trip = page.locator('article.card');
  await expect(trip.getByRole('button', { name: 'Approve' })).toBeDisabled();
  await trip.getByLabel('Decision or void reason').fill('Checked against the paper log');
  await trip.getByRole('button', { name: 'Approve' }).click();
  await expect(trip).not.toContainText('awaits another person');
});

async function chooseDateTime(page: Page, id: string, value: string) {
  const field = page.locator(`#${id}`);
  await field.click();
  await field.fill('');
  await field.pressSequentially(value.replace('T', ' '));
  await page.locator('s2s-date-time-picker').filter({ has: field }).getByRole('button', { name: 'Apply' }).click();
  await expect(field).toHaveValue(value.replace('T', ' '));
}

test('phone departure, return, overrun draft and short asset recovery', async ({ page }) => {
  test.setTimeout(90_000);
  await page.setViewportSize({ width: 375, height: 812 });
  await mockApi(page, [ADMIN, OPS]);
  const api = await mockMovementsApi(page);
  await signIn(page);
  await page.goto('/jobs/job-1/movements');
  await expect(page.getByRole('heading', { name: 'Jetty departures and returns' })).toBeVisible();
  const form = page.locator('form').first();
  await expect(form).not.toContainText('Craft');
  await expect(form.getByRole('button', { name: 'Record departure' })).toBeDisabled();
  await chooseDateTime(page, 'movement-departed', '2026-10-10T05:30');
  await form.getByRole('checkbox', { name: /I confirm the entered jetty departure/ }).check();
  await form.getByRole('button', { name: 'Record departure' }).click();
  await expect(page.locator('article.card')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Record departure' })).toHaveCount(0);
  expect(api.trips[0]?.departedAt).toBe('2026-10-10T00:00:00.000Z');
  expect(api.trips[0]?.craftId).toBeNull();
  expect(api.trips[0]?.assets.map(a => a.allocationId)).toEqual(['allocation-1']);
  expect(api.trips[0]?.returnedAt).toBeNull();
  const clock = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Chargeable clock' }) });
  await expect(clock).toContainText(/Oct 10, 2026/);
  await chooseDateTime(page, 'return-trip-1', '2026-10-12T06:40');
  await page.locator('article.card').getByRole('button', { name: 'Record return' }).click();
  await expect(page.locator('article.card')).toContainText(/Oct 12, 2026/);
  await expect(page.locator('article.card').getByRole('button', { name: 'Record return' })).toHaveCount(0);
  await expect(clock).toContainText('Exceeded by');
  await expect(clock).toContainText('DRAFT');
  await expect(clock).toContainText('Pending tariff terms');
  await page.getByRole('link', { name: 'Demobilisation and recovery' }).click();
  await expect(page.getByRole('heading', { name: 'Demobilisation and recovery' })).toBeVisible();
  await chooseDateTime(page, 'disembark-time', '2026-10-12T07:00');
  await page.getByRole('button', { name: 'Save' }).first().click();
  await expect(page.getByRole('heading', { name: 'Disembarkation' }).locator('..')).toContainText(/Oct 12, 2026/);
  const asset = page.locator('article.card').first();
  await asset.getByLabel('Return status').selectOption('SHORT');
  await asset.getByLabel('Notes').fill('One synthetic fender short');
  await expect(asset.getByLabel('Return status')).toHaveValue('SHORT');
  await asset.getByRole('button', { name: 'Save' }).click();
  await expect(asset).toContainText('Return status: SHORT');
  await expect(page.getByText(/1 dispatched assets still need/)).toBeVisible();
  await page.getByLabel('Reason for change or decision').fill('Synthetic discrepancy resolved');
  await asset.getByRole('button', { name: 'Resolve discrepancy' }).click();
  await expect(page.getByRole('button', { name: 'Close recovery' })).toBeEnabled();
  await page.getByRole('button', { name: 'Close recovery' }).click();
  await expect(page.getByRole('heading', { name: 'Disembarkation' }).locator('..')).toContainText('CLOSED');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('departure accepts future, past, and current times without craft', async ({ page }) => {
  await mockApi(page, [ADMIN, OPS]);
  const api = await mockMovementsApi(page);
  await page.route('**/api/v1/jobs/job-1/movements/context', route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify({ jobNumber: 'SYN-JOB-1', timeZone: 'Asia/Kolkata' }) }));
  await signIn(page);
  await page.goto('/jobs/job-1/movements');
  const form = page.locator('form').first();
  await expect(form).not.toContainText('Craft');
  await expect(page.locator('#movement-departed')).not.toBeEmpty();
  await expect(form.getByRole('button', { name: 'Record departure' })).toBeDisabled();
  await form.getByRole('checkbox', { name: /I confirm the entered jetty departure/ }).check();
  await chooseDateTime(page, 'movement-departed', '2100-10-10T05:30');
  await form.getByRole('button', { name: 'Record departure' }).click();
  await expect(page.locator('article.card')).toHaveCount(1);
  expect(api.trips[0]?.departedAt).toBe('2100-10-10T00:00:00.000Z');
  await expect(page.getByRole('button', { name: 'Record departure' })).toHaveCount(0);
  await expect(page.locator('section').filter({ has: page.getByRole('heading', { name: 'Chargeable clock' }) })).toContainText(/Oct 10, 2100/);
});
