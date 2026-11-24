import { Locator, Page, expect, test } from '@playwright/test';
import { MARINE, TECHNICAL, mockApi } from './mock-api';
import { mockJpoApi } from './mock-jpo-api';
import { mockWeatherApi } from './mock-weather-api';

async function signIn(page: Page, username: 'technical' | 'marine') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(username);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
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

test('limits are approved by someone else, a forecast is recorded and accepted, and values beyond a limit warn', async ({ page }) => {
  test.setTimeout(120_000);
  await mockApi(page, [TECHNICAL, MARINE]);
  const api = await mockWeatherApi(page);

  // --- The technical officer enters the limits and a forecast ---
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1');
  await page.getByRole('link', { name: 'Weather', exact: true }).click();
  await expect(page).toHaveURL(/\/jobs\/job-1\/weather$/);
  await expect(page.getByText('No operating limits have been entered for this job yet.')).toBeVisible();
  await expect(page.getByText('No operating limits are approved for this job yet')).toBeVisible();
  await page.getByRole('button', { name: 'Enter operating limits' }).click();
  await page.getByRole('button', { name: 'Add a limit' }).click();
  await choose(page, 'lim-p-0', 'Wind speed (kn)');
  await page.locator('#lim-o-0').fill('Mooring');
  await page.locator('#lim-t-0').fill('20');
  await page.locator('#lim-a-0').fill('Stop and review');
  await expect(page.locator('.limits').getByText('kn', { exact: true })).toBeVisible();
  await expect(page.getByText('Save your changes before submitting.')).toBeVisible();
  await page.locator('.limits').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => api.limitSaves.length).toBe(1);
  expect(api.limitSaves[0]['rows']).toEqual([
    { parameterCode: 'WIND_SPEED', operation: 'Mooring', comparison: 'ABOVE', threshold: '20', action: 'Stop and review' },
  ]);
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.locator('.revisions')).toContainText('Submitted');
  await expect(page.getByText('You submitted these limits, so someone else must approve them.')).toBeVisible();
  await expect(page.locator('#lim-o-0')).toBeDisabled();
  await noHorizontalScroll(page);

  await page.getByRole('button', { name: 'Record a weather report' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('#r-issued')).not.toHaveValue('');
  await fillIn(dialog.locator('#r-provider'), 'Synthetic Weather Ltd');
  await fillIn(dialog.locator('#t-WIND_SPEED-0'), '25');
  await fillIn(dialog.locator('#t-WAVE_HEIGHT-0'), '1.5');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(api.reports[0]).toMatchObject({ kind: 'FORECAST', source: 'PROVIDER_REPORT', providerName: 'Synthetic Weather Ltd', hasFile: false });
  expect((api.reports[0]['values'] as { parameterCode: string; value: string }[]).map((v) => [v.parameterCode, v.value]))
    .toEqual([['WIND_SPEED', '25'], ['WAVE_HEIGHT', '1.5']]);
  const report = page.locator('article.report').first();
  await expect(report).toContainText('Recorded');
  await expect(report).toContainText('Recorded by Demo Technical Officer');
  await expect(report.getByRole('button', { name: 'Accept' })).toHaveCount(0);
  await signOut(page);

  // --- The marine superintendent approves the limits and accepts the forecast ---
  await signIn(page, 'marine');
  await page.goto('/jobs/job-1/weather');
  await page.locator('.limits').getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(page.locator('.revisions')).toContainText('Approved');
  await expect(page.locator('.warnings')).toContainText('Forecast for');
  await expect(page.locator('.warnings')).toContainText('wind speed 25 kn is above the 20 kn limit for Mooring. Action: Stop and review');
  await expect(page.locator('.warnings')).toContainText('Not reviewed yet');

  await page.locator('article.report').first().getByRole('button', { name: 'Accept' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Accept' }).click();
  await expect(page.locator('article.report').first()).toContainText('accepted by Demo Marine Superintendent');
  await expect(page.locator('.warnings')).not.toContainText('Not reviewed yet');
  expect(api.reviews.at(-1)).toMatchObject({ decision: 'ACCEPT' });
  await noHorizontalScroll(page);
});

test('the JPO shows the approved limits as linked, and parameters are managed in the catalogue', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [TECHNICAL, MARINE]);
  const weather = await mockWeatherApi(page);
  await mockJpoApi(page, { annexPublished: true, linkedLimits: true });

  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/jpo');
  await page.getByRole('button', { name: 'Prepare the JPO' }).click();
  const detail = page.locator('article.detail');
  await expect(detail.getByText("The job's approved operating limits (revision 1).", { exact: false })).toBeVisible();
  await expect(detail).toContainText('Wind speed');
  await expect(detail).toContainText('Above 20 kn');
  await expect(detail.getByRole('button', { name: 'Add a limit' })).toHaveCount(0);
  await expect(page.locator('.blockers')).not.toContainText('operating limits');
  await expect(detail.getByRole('link', { name: /Weather and operating limits/ })).toHaveAttribute('href', '/jobs/job-1/weather');
  await noHorizontalScroll(page);

  await page.goto('/technical/weather-parameters');
  await expect(page.getByRole('heading', { name: 'Weather parameters' })).toBeVisible();
  await expect(page.locator('table')).toContainText('Significant wave height');
  await page.getByRole('button', { name: 'Add a parameter' }).click();
  const dialog = page.getByRole('dialog');
  await fillIn(dialog.locator('#wp-code'), 'SWELL_HEIGHT');
  await fillIn(dialog.locator('#wp-label'), 'Swell height');
  await fillIn(dialog.locator('#wp-unit'), 'm');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('table')).toContainText('Swell height');
  expect(weather.parameterSaves[0]).toMatchObject({ code: 'SWELL_HEIGHT', label: 'Swell height', unit: 'm', active: true, sortOrder: 40 });
});
