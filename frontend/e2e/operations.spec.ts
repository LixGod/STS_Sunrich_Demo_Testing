import { Locator, Page, expect, test } from '@playwright/test';
import { mockApi } from './mock-api';
import { OPERATOR, REVIEWER, mockOperationsApi } from './mock-operations-api';

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

async function choose(page: Page, inputId: string, option: string | RegExp) {
  await page.locator(`#${inputId}`).click();
  await page.getByRole('option', { name: option }).last().click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
}

test('operation steps stay separate for mother and daughter, while Both appears on each vessel', async ({ page }) => {
  await mockApi(page, [OPERATOR, REVIEWER]);
  const api = await mockOperationsApi(page);
  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/operations');
  await expect(page.getByRole('heading', { name: 'Operation steps' })).toBeVisible();
  await page.getByText('Event log').click();
  const mother = page.locator('section.vessel-events').filter({ has: page.getByRole('heading', { name: /Discharging vessel/ }) });
  const daughter = page.locator('section.vessel-events').filter({ has: page.getByRole('heading', { name: /Receiving vessel/ }) });

  await page.getByRole('button', { name: 'Other event' }).click();
  await choose(page, 'ev-type', 'All fast');
  await choose(page, 'ev-vessel', /Discharging vessel/);
  await page.getByRole('dialog', { name: 'Record an event' }).getByRole('button', { name: 'Save' }).click();
  await expect(mother.locator('article.item')).toHaveCount(1);
  await expect(daughter).toContainText('No events recorded yet.');
  expect(api.events[0]['jobVesselId']).toBe('jv-1');
  await expect(page.getByRole('checkbox', { name: 'All fast · Discharging vessel · DEMO MOTHER' })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'All fast · Receiving vessel · DEMO DAUGHTER' })).not.toBeChecked();

  await page.getByRole('button', { name: 'Other event' }).click();
  await choose(page, 'ev-type', 'All fast');
  await page.locator('#ev-vessel').click();
  await expect(page.getByRole('option', { name: 'Both vessels' })).toHaveCount(0);
  await expect(page.getByRole('option', { name: /Discharging vessel/ })).toHaveCount(0);
  await page.getByRole('option', { name: /Receiving vessel/ }).last().click();
  await page.getByRole('dialog', { name: 'Record an event' }).getByRole('button', { name: 'Save' }).click();
  await expect(daughter.locator('article.item')).toHaveCount(1);
  expect(api.events[1]['jobVesselId']).toBe('jv-2');
  await expect(page.getByRole('checkbox', { name: 'All fast · Receiving vessel · DEMO DAUGHTER' })).toBeChecked();

  await page.getByRole('checkbox', { name: 'POAC boarded · Discharging vessel · DEMO MOTHER' }).check();
  await expect(mother.locator('article.item')).toHaveCount(2);
  await expect(daughter.locator('article.item')).toHaveCount(1);
  await expect(page.getByRole('checkbox', { name: 'POAC boarded · Receiving vessel · DEMO DAUGHTER' })).not.toBeChecked();
  expect(api.events[2]['jobVesselId']).toBe('jv-1');
  await page.getByRole('checkbox', { name: 'POAC boarded · Receiving vessel · DEMO DAUGHTER' }).check();
  await expect(daughter.locator('article.item')).toHaveCount(2);
  expect(api.events[3]['jobVesselId']).toBe('jv-2');

  await page.getByRole('button', { name: 'Other event' }).click();
  await choose(page, 'ev-type', 'Cargo transfer commenced');
  await choose(page, 'ev-vessel', 'Both vessels');
  await page.getByRole('dialog', { name: 'Record an event' }).getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('checkbox', { name: 'Cargo transfer commenced · Discharging vessel · DEMO MOTHER' })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'Cargo transfer commenced · Receiving vessel · DEMO DAUGHTER' })).toBeChecked();
  expect(api.events[4]['jobVesselId']).toBeNull();
  await expect(page.getByText(/Device captured|Server received/)).toHaveCount(0);
});

test('events, cargo readings, an incident closed by a reviewer, and a SOF issued by a second person, on desktop and phones', async ({ page }) => {
  test.setTimeout(120_000);
  await mockApi(page, [OPERATOR, REVIEWER]);
  const api = await mockOperationsApi(page);

  // --- Operations records events and readings ---
  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/operations');
  await page.getByRole('link', { name: /JOB\/2026\/00001/ }).click();
  await expect(page).toHaveURL(/\/jobs\/job-1\/operations$/);
  await expect(page.getByText('Times are shown in Asia/Kolkata.')).toBeVisible();
  await page.getByRole('button', { name: 'ETA and progress' }).click();
  await expect(page.locator('.card').first()).toContainText('DEMO MOTHER');
  await noHorizontalScroll(page);
  await page.getByRole('button', { name: 'Operation steps' }).click();

  // Ticking a step records it now for the selected vessel, without a dialog.
  await page.getByRole('checkbox', { name: 'All fast · Discharging vessel · DEMO MOTHER' }).check();
  await expect.poll(() => api.events.length).toBe(1);
  expect(api.events[0]).toMatchObject({ typeCode: 'ALL_FAST', source: 'LIVE', startedAt: null, jobVesselId: 'jv-1' });
  expect(api.events[0]['occurredAt']).toBeTruthy();
  await expect(page.getByRole('checkbox', { name: 'All fast · Discharging vessel · DEMO MOTHER' })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'All fast · Receiving vessel · DEMO DAUGHTER' })).not.toBeChecked();
  const item = page.locator('article.item').first();
  await expect(item).toContainText('All fast');

  // Changing the time is a correction and needs a reason.
  await page.getByRole('button', { name: 'Change time' }).click();
  let dialog = page.getByRole('dialog', { name: 'Correct the event' });
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeVisible();
  await fillIn(dialog.locator('#ev-reason'), 'Time from the deck log');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(api.events[1]).toMatchObject({ typeCode: 'ALL_FAST', reason: 'Time from the deck log' });
  await expect(item).toContainText('Corrected');

  await page.getByRole('button', { name: 'Cargo', exact: true }).click();
  await page.getByRole('button', { name: 'Add a reading' }).click();
  dialog = page.getByRole('dialog', { name: 'Reading: Propane' });
  await fillIn(dialog.locator('#rd-value'), '1000');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('table.grid')).toContainText('1000');
  await page.getByRole('button', { name: 'Add a reading' }).click();
  dialog = page.getByRole('dialog', { name: 'Reading: Propane' });
  await fillIn(dialog.locator('#rd-value'), '900');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toContainText('The cumulative total cannot fall. Record a correction with a reason.');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(api.readings.map((r) => [r['cumulative'], r['unitCode'], r['kind']])).toEqual([['1000', 'MT', 'NORMAL'], ['900', 'MT', 'NORMAL']]);

  await page.getByRole('button', { name: /Delays and incidents/ }).click();
  await page.getByRole('button', { name: 'Report an incident' }).click();
  dialog = page.getByRole('dialog', { name: 'Report an incident' });
  await choose(page, 'in-cat', 'Near miss');
  await choose(page, 'in-sev', 'Low');
  await fillIn(dialog.locator('#in-desc'), 'Synthetic near miss during hose handling');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('article.item').last()).toContainText('Reported');
  await expect(page.getByRole('button', { name: 'Close the incident' })).toHaveCount(0); // the reporter cannot close it

  await page.getByRole('button', { name: 'Statement of Facts' }).click();
  await page.getByRole('button', { name: 'Prepare a SOF revision' }).click();
  await expect(page.locator('article.item').first()).toContainText('Prepared');
  await expect(page.getByText('You prepared this revision, so someone else must issue it.')).toBeVisible();
  await noHorizontalScroll(page);
  await signOut(page);

  // --- The reviewer closes the incident and issues the SOF ---
  await signIn(page, 'ops.manager', 'ops');
  await page.goto('/jobs/job-1/operations');
  await page.getByRole('button', { name: /Delays and incidents/ }).click();
  await page.getByRole('button', { name: 'Start review' }).click();
  await expect(page.locator('article.item').last()).toContainText('Under review');
  await page.getByRole('button', { name: 'Close the incident' }).click();
  await fillIn(page.getByRole('dialog').locator('#reason-input'), 'Briefing held, procedure updated');
  await page.getByRole('dialog').getByRole('button', { name: 'Close the incident' }).click();
  await expect(page.locator('article.item').last()).toContainText('Closed by Demo Operations Manager');
  expect(api.reviews).toEqual([{ decision: 'START_REVIEW', note: null }, { decision: 'CLOSE', note: 'Briefing held, procedure updated' }]);

  await page.getByRole('button', { name: 'Statement of Facts' }).click();
  await page.getByRole('button', { name: 'Issue' }).click();
  await expect(page.locator('article.item').first()).toContainText('Issued');
  await expect(page.locator('article.item').first().getByRole('link', { name: 'Issued PDF' }))
    .toHaveAttribute('href', /\/api\/v1\/jobs\/job-1\/operations\/sof\/sof-1\/pdf$/);
  await noHorizontalScroll(page);
});

test('Operations managers add entries to the operations lists', async ({ page }) => {
  test.setTimeout(60_000);
  await mockApi(page, [OPERATOR, REVIEWER]);
  const api = await mockOperationsApi(page);
  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/operations/lists');
  await expect(page.getByRole('heading', { name: 'Operations lists' })).toBeVisible();
  await expect(page.locator('table')).toContainText('Hose connection');
  await page.getByRole('button', { name: 'Add an entry' }).click();
  const dialog = page.getByRole('dialog');
  await fillIn(dialog.locator('#li-code'), 'GANGWAY_RIGGED');
  await fillIn(dialog.locator('#li-label'), 'Gangway rigged');
  await choose(page, 'li-phase', 'Berthing');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('table')).toContainText('Gangway rigged');
  expect(api.listSaves[0]).toMatchObject({ code: 'GANGWAY_RIGGED', label: 'Gangway rigged', phase: 'BERTHING', interval: false, active: true });
  await noHorizontalScroll(page);
});
