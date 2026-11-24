import { Locator, Page, expect, test } from '@playwright/test';
import { mockApi } from './mock-api';
import { MANAGER, PREPARER, mockFinalReportApi } from './mock-final-report-api';

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

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
}

test('the final report is prepared from the records, approved by someone else and issued, on desktop and phones', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [PREPARER, MANAGER]);
  const api = await mockFinalReportApi(page);

  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/final-report');
  await page.getByRole('button', { name: 'Prepare the final report' }).click();
  await expect(page.locator('#fr-summary')).toHaveValue(/No incidents or delays were recorded in the operations log\./);
  await expect(page.locator('.blockers')).toContainText('Enter the supervision and attendance');
  await page.locator('.blockers').getByRole('link', { name: /supervision and attendance/ }).click();
  await expect(page.locator('#fr-attendance')).toBeInViewport();
  await expect(page.getByText('From job data')).toBeVisible();
  await expect(page.locator('.facts')).toContainText('demo-q88.pdf');
  await noHorizontalScroll(page);

  await fillIn(page.locator('#fr-vhf'), 'VHF 72');
  await page.getByRole('button', { name: 'Add a person' }).click();
  await fillIn(page.locator('#at-c-0'), 'Mooring Master');
  await fillIn(page.locator('#at-n-0'), 'Demo Mooring Master');
  await page.getByRole('button', { name: 'Add equipment' }).click();
  await fillIn(page.locator('#eq-d-0'), 'Synthetic primary fender 3.3 m x 6.5 m');
  await fillIn(page.locator('#eq-q-0'), '4');
  await expect(page.getByText('Save your changes before submitting.')).toBeVisible();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.saves.length).toBe(1);
  expect(api.saves[0]).toMatchObject({
    vhfChannel: 'VHF 72', attendance: [{ capacity: 'Mooring Master', name: 'Demo Mooring Master' }],
    equipment: [{ category: 'FENDER', description: 'Synthetic primary fender 3.3 m x 6.5 m', quantity: 4 }],
  });
  await expect(page.locator('.blockers')).toHaveCount(0);
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.locator('.head s2s-status-tag').first()).toContainText('Submitted');
  await expect(page.locator('#fr-summary')).toBeDisabled();
  await signOut(page);

  await signIn(page, 'approver', 'approver');
  await page.goto('/jobs/job-1/final-report');
  await page.locator('.actions').getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('.head s2s-status-tag').first()).toContainText('Approved');
  expect(api.decisions).toEqual([{ decision: 'APPROVE', reason: null }]);
  await signOut(page);

  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/final-report');
  await page.locator('.actions').getByRole('button', { name: 'Issue' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Issue' }).click();
  await expect(page.locator('.head s2s-status-tag').first()).toContainText('Issued');
  await expect(page.getByRole('link', { name: 'Issued PDF' })).toHaveAttribute('href', /\/api\/v1\/jobs\/job-1\/final-report\/revisions\/fr-1\/pdf$/);
  await expect(page.getByText('d'.repeat(64))).toBeVisible();
  await noHorizontalScroll(page);
});
