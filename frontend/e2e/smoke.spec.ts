import { expect, test } from '@playwright/test';
import { ADMIN, OPS, STS_ADMIN, mockApi } from './mock-api';

async function signIn(page: import('@playwright/test').Page, username: string, password: string) {
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function openNav(page: import('@playwright/test').Page) {
  const toggle = page.getByRole('button', { name: 'Show or hide the menu' });
  if (await toggle.isVisible()) await toggle.click();
}

test('deep link goes through sign-in and back, then lists branches', async ({ page }) => {
  await mockApi(page, [ADMIN, STS_ADMIN]);
  await page.goto('/administration/branches');
  await expect(page).toHaveURL(/\/login\?returnUrl=%2Fadministration%2Fbranches/);

  await signIn(page, 'sts.admin', 'wrong');
  await expect(page.getByRole('alert')).toContainText('Invalid username or password');

  await signIn(page, 'sts.admin', 'sts');
  await expect(page).toHaveURL(/\/administration\/branches$/);
  await expect(page.getByRole('heading', { name: 'Branches' })).toBeVisible();
  await expect(page.getByText('Demo Mumbai Branch')).toBeVisible();
});

test('Super Admin can open setup and user access pages', async ({ page }) => {
  await mockApi(page, [ADMIN]);
  await page.goto('/login');
  await signIn(page, 'admin', 'admin');
  await page.goto('/administration/branches');
  await expect(page.getByRole('heading', { name: 'Branches' })).toBeVisible();
  await page.goto('/administration/users');
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
});

test('STS Admin creates a branch', async ({ page }) => {
  await mockApi(page, [STS_ADMIN]);
  await page.goto('/login');
  await signIn(page, 'sts.admin', 'sts');
  await page.goto('/administration/branches/new');

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('This field is required.').first()).toBeVisible();

  await page.getByLabel('Code').fill('SIN');
  await page.getByLabel('Name').fill('Demo Singapore Branch');
  await page.locator('#timezone').click();
  await page.getByRole('searchbox').last().fill('Singapore');
  await page.getByRole('option', { name: 'Asia/Singapore' }).click();
  await page.getByRole('button', { name: 'Save' }).click();

  await expect(page).toHaveURL(/\/administration\/branches\/br-2$/);
  await expect(page.getByRole('heading', { name: 'Demo Singapore Branch' })).toBeVisible();
  await expect(page.getByLabel('Code')).toBeDisabled();
});

test('duplicate code is reported next to the field', async ({ page }) => {
  await mockApi(page, [STS_ADMIN]);
  await page.goto('/login');
  await signIn(page, 'sts.admin', 'sts');
  await page.goto('/administration/branches/new');
  await page.getByLabel('Code').fill('MUM');
  await page.getByLabel('Name').fill('Duplicate');
  await page.locator('#timezone').click();
  await page.getByRole('searchbox').last().fill('Kolkata');
  await page.getByRole('option', { name: 'Asia/Kolkata' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('This code is already in use.')).toBeVisible();
});

test('navigation only offers permitted items and blocks the rest', async ({ page }) => {
  await mockApi(page, [OPS]);
  await page.goto('/login');
  await signIn(page, 'ops.mumbai', 'ops');
  await expect(page.getByRole('heading', { name: /Welcome, Demo Operations/ })).toBeVisible();
  // The dashboard puts work first and shows only what the user may see: no finance for operations.
  await expect(page.locator('.kpi', { hasText: 'Open actions' })).toBeVisible();
  await expect(page.getByText('Finance overview')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);

  await openNav(page);
  const nav = page.getByRole('navigation', { name: 'Main navigation' });
  await expect(nav.getByRole('link', { name: 'Branches' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Users' })).toHaveCount(0);
  // Every module is now live, so the planned-modules section is gone.
  await expect(nav.getByText('Planned modules')).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Enquiries' })).toHaveCount(0); // OPS fixture has no enquiry permission

  await page.goto('/administration/users');
  await expect(page.getByText('You do not have permission to open this page.')).toBeVisible();
});

test('sign-out returns to the login page', async ({ page }) => {
  await mockApi(page);
  await page.goto('/login');
  await signIn(page, 'admin', 'admin');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
});
