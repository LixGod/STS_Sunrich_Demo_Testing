import { expect, test } from '@playwright/test';

async function signIn(page: import('@playwright/test').Page, username: string, password: string) {
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test.beforeAll(async ({ request }) => {
  const response = await request.get('http://127.0.0.1:8080/v3/api-docs');
  expect(response.ok(), 'The local backend with synthetic users must be running').toBeTruthy();
});

test('invalid credentials, session restoration and sign-out use the real session', async ({ page }) => {
  await page.goto('/login');
  await signIn(page, 'finance', 'incorrect');
  await expect(page.getByRole('alert')).toContainText('Invalid username or password');

  await signIn(page, 'finance', 'finance');
  await expect(page).toHaveURL(/\/dashboard$/);
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/finance/invoices');
  await expect(page).toHaveURL(/\/login\?returnUrl=/);
});

test('finance permissions are enforced in the UI and by the backend', async ({ page }) => {
  await page.goto('/login');
  await signIn(page, 'finance', 'finance');
  await expect(page).toHaveURL(/\/dashboard$/);
  await page.goto('/administration/users');
  await expect(page.getByText('You do not have permission to open this page.')).toBeVisible();

  const response = await page.evaluate(async () => {
    const result = await fetch('/api/v1/admin/users');
    return { status: result.status, code: (await result.json()).code };
  });
  expect(response.status).toBe(403);
  expect(response.code).toBe('ACCESS_DENIED');
});

test('job list uses the backend page envelope and validates sorting', async ({ page }) => {
  await page.goto('/login');
  await signIn(page, 'admin', 'admin');
  await expect(page).toHaveURL(/\/dashboard$/);
  const result = await page.evaluate(async () => {
    const valid = await fetch('/api/v1/jobs?page=0&size=10&sort=jobNumber,asc');
    const invalid = await fetch('/api/v1/jobs?page=0&size=10&sort=unknown,asc');
    return {
      validStatus: valid.status,
      validBody: await valid.json(),
      invalidStatus: invalid.status,
      invalidBody: await invalid.json(),
    };
  });
  expect(result.validStatus).toBe(200);
  expect(result.validBody).toMatchObject({ page: 0, size: 10 });
  expect(Array.isArray(result.validBody.items)).toBe(true);
  expect(typeof result.validBody.totalElements).toBe('number');
  expect(result.invalidStatus).toBe(400);
  expect(result.invalidBody.code).toBe('INVALID_SORT');
});

test('an expired server session sends an open screen back to sign-in', async ({ page }) => {
  await page.goto('/login');
  await signIn(page, 'admin', 'admin');
  await expect(page).toHaveURL(/\/dashboard$/);
  const logoutStatus = await page.evaluate(async () => {
    const token = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)?.[1] ?? '';
    const response = await fetch('/api/v1/auth/logout', {
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
    });
    return response.status;
  });
  expect(logoutStatus).toBe(204);
  await page.locator('a[href="/jobs"]').first().evaluate((link: HTMLAnchorElement) => link.click());
  await expect(page).toHaveURL(/\/login\?.*expired=1/);
});
