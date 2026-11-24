import { expect, test } from '@playwright/test';
import { STS_ADMIN, mockApi } from './mock-api';
import { mockCommercialApi } from './mock-commercial-api';

// Regression: the shared master-list page once failed to render because the closed dialog bound to an empty form.
test('a configurable master list renders, opens a record and saves it with its version', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await mockApi(page, [STS_ADMIN]);
  await mockCommercialApi(page);
  const updates: { body: unknown; ifMatch: string | undefined }[] = [];
  const unit = { id: 'u-9', code: 'BBL', name: 'Barrel', dimension: 'VOLUME', active: true, updatedAt: null, version: 4 };
  await page.route('**/api/v1/masters/units**', async (route) => {
    if (route.request().method() === 'PUT') {
      updates.push({ body: route.request().postDataJSON(), ifMatch: route.request().headers()['if-match'] });
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ...unit, name: 'Oil barrel', version: 5 }) });
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [unit], page: 0, size: 25, totalElements: 1 }) });
  });

  await page.goto('/login');
  await page.getByLabel('Username').fill('sts.admin');
  await page.locator('#password').fill('sts');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.goto('/administration/units');

  await expect(page.getByRole('heading', { name: 'Units' })).toBeVisible();
  await expect(page.getByText('Barrel')).toBeVisible();
  await expect(page.getByText('Volume')).toBeVisible(); // enum label translated

  await page.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('#f-code')).toBeDisabled();
  await dialog.locator('#f-name').fill('Oil barrel');
  await dialog.getByRole('button', { name: 'Save' }).click();

  await expect.poll(() => updates.length).toBe(1);
  expect(updates[0].ifMatch).toBe('"4"');
  expect(updates[0].body).toEqual({ code: 'BBL', name: 'Oil barrel', dimension: 'VOLUME' });
  expect(errors).toEqual([]);
});
