import { Page, expect, test } from '@playwright/test';
import { COMMERCIAL, mockApi } from './mock-api';
import { mockCommercialApi } from './mock-commercial-api';

/** Opens a select, picks an option and waits for its panel to close (so the next select's options are unambiguous). */
async function choose(page: Page, inputId: string, option: string) {
  await page.locator(`#${inputId}`).click();
  await page.getByRole('option', { name: option }).last().click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

async function pick(page: Page, inputId: string, text: string, option: string | RegExp) {
  await page.locator(`#${inputId}`).fill(text);
  await page.getByRole('option', { name: option }).first().click();
}

test('commercial user registers an enquiry, sees the duplicate warning, and marks it lost with a reason', async ({ page }) => {
  test.setTimeout(60_000); // one long journey; WebKit under parallel load needs more than the 30 s default
  await mockApi(page, [COMMERCIAL]);
  const api = await mockCommercialApi(page);

  await page.goto('/login');
  await page.getByLabel('Username').fill('commercial');
  await page.locator('#password').fill('commercial');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();

  await page.goto('/commercial/enquiries');
  await page.getByRole('button', { name: 'New enquiry' }).click();
  await expect(page).toHaveURL(/\/commercial\/enquiries\/new$/);

  // With only one branch it is chosen automatically; contacts wait for the client.
  await expect(page.locator('#branchId')).toHaveText('MUM — Demo Mumbai Branch');
  await expect(page.locator('#clientContactId')).toHaveText('Choose a client first');
  await page.locator('#clientContactId').click();
  await expect(page.getByRole('listbox').getByText('Choose a client first')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toHaveCount(0);
  await pick(page, 'client', 'Demo', /DEMO Charterers Ltd/);
  await expect(page.locator('#clientCountry')).toHaveValue('United Arab Emirates');
  await expect(page.locator('#clientPhone')).toHaveValue('+971 50 123 4567');
  await pick(page, 'mother-vessel', 'mother', /DEMO MOTHER/);
  await pick(page, 'daughter-vessel', '1000019', /DEMO DAUGHTER/);

  await expect(page.getByText('Other open enquiries exist for the same two vessels:')).toBeVisible();

  await choose(page, 'grade-0', 'PROPANE — Propane');
  await expect(page.locator('#cargoTypes')).toHaveValue('LPG');
  await page.locator('p-multiselect').click();
  await page.getByRole('option', { name: 'Chemical' }).click();
  await page.keyboard.press('Escape');
  await page.locator('#qty-0').fill('34000.5');
  await expect(page.locator('#unit-0')).toContainText('MT'); // default unit of the grade
  await page.getByRole('button', { name: 'Add cargo grade' }).click();
  await choose(page, 'grade-1', 'BUTANE — Butane');
  await page.locator('#qty-1').fill('12000');

  await page.getByRole('button', { name: 'Register enquiry' }).click();
  await expect(page).toHaveURL(/\/commercial\/enquiries\/enq-1$/);
  await expect(page.getByRole('heading', { name: 'ENQ/2026/00001' })).toBeVisible();

  const sent = api.created[0];
  expect(sent.body['cargoTypes']).toEqual(['LPG', 'CHEMICAL']);
  expect(sent.headers['idempotency-key']).toBeTruthy();
  expect(sent.body['cargo']).toEqual([
    { cargoGradeId: 'g-1', quantity: '34000.5', unitId: 'u-1' },
    { cargoGradeId: 'g-2', quantity: '12000', unitId: 'u-1' },
  ]);
  const mother = sent.body['motherVessel'] as { vesselId: string };
  expect(mother.vesselId).toBe('v-1');

  // Losing an enquiry needs a reason, then it becomes read-only. Wait for the save toast to clear the header actions.
  await expect(page.locator('.p-toast-message')).toHaveCount(0, { timeout: 10_000 });
  await page.getByRole('button', { name: 'Mark as lost' }).click();
  const dialog = page.getByRole('dialog');
  // A typed reason survives leaving the field (regression: the dialog used to reset itself on blur).
  await dialog.getByLabel(/Reason/).fill('Draft reason');
  await dialog.getByLabel(/Reason/).blur();
  await expect(dialog.getByLabel(/Reason/)).toHaveValue('Draft reason');
  await dialog.getByLabel(/Reason/).fill('');
  await dialog.getByRole('button', { name: 'Confirm' }).click();
  await expect(dialog.getByText('This field is required.')).toBeVisible();
  await dialog.getByLabel(/Reason/).fill('Client chose another operator');
  await dialog.getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByText('This enquiry can no longer be edited in its current status.')).toBeVisible();
  await expect(page.getByText('Client chose another operator')).toBeVisible();
});

test('new client country and phone appear on the enquiry before the detail reload finishes', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  let releaseDetails!: () => void;
  const detailGate = new Promise<void>((resolve) => { releaseDetails = resolve; });
  const created = { id: 'c-new', code: 'CL-NEW', legalName: 'New Fleet', countryCode: 'IN', phone: '+91 9876543210',
    emails: ['ops@newfleet.test'], phones: ['+91 9876543210'], active: true };
  await page.route('**/api/v1/masters/clients/quick-add', (route) => route.fulfill({ status: 201, contentType: 'application/json',
    body: JSON.stringify({ client: created, contact: { id: 'contact-new', email: 'ops@newfleet.test', phone: created.phone } }) }));
  await page.route('**/api/v1/masters/clients/c-new', async (route) => {
    await detailGate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(created) });
  });
  await page.route('**/api/v1/masters/clients/c-new/contacts', (route) => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify([{ id: 'contact-new', fullName: 'New Fleet', email: 'ops@newfleet.test', active: true }]) }));
  await page.goto('/login');
  await page.getByLabel('Username').fill('commercial');
  await page.locator('#password').fill('commercial');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.goto('/commercial/enquiries/new');
  await page.getByRole('button', { name: 'Add client' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.locator('#quick-client-name').fill('New Fleet');
  await dialog.locator('#quick-client-email').fill('ops@newfleet.test');
  await choose(page, 'quick-client-country', 'IN — India');
  await dialog.locator('#quick-client-phone').fill('9876543210');
  await dialog.getByRole('button', { name: 'Save' }).click();
  try {
    await expect(page.locator('#clientCountry')).toHaveValue('India', { timeout: 1000 });
    await expect(page.locator('#clientPhone')).toHaveValue('+91 9876543210', { timeout: 1000 });
  } finally {
    releaseDetails();
  }
  await expect(page.locator('#clientCountry')).toHaveValue('India');
  await expect(page.locator('#clientPhone')).toHaveValue('+91 9876543210');
});

test('a vessel added from the enquiry form is registered and selected', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  await page.goto('/login');
  await page.getByLabel('Username').fill('commercial');
  await page.locator('#password').fill('commercial');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
  await page.goto('/commercial/enquiries/new');

  await page.getByRole('button', { name: 'Add vessel' }).first().click();
  const dialog = page.getByRole('dialog');
  // Fill every field, leaving each one (blur) before the next: entries must survive (regression: blur reset the form).
  await dialog.locator('#qa-imo').fill('1000033');
  await dialog.locator('#qa-name').click();
  await dialog.locator('#qa-name').fill('DEMO NEW SHIP');
  await choose(page, 'qa-type', 'Product tanker');
  await expect(dialog.locator('#qa-imo')).toHaveValue('1000033');
  await expect(dialog.locator('#qa-name')).toHaveValue('DEMO NEW SHIP');
  await expect(dialog.locator('#qa-type')).toHaveText('Product tanker');

  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('#mother-vessel')).toHaveValue('DEMO NEW SHIP · IMO 1000033');
});

test('a failed draft cleanup warns after the enquiry is registered', async ({ page }) => {
  await mockApi(page, [COMMERCIAL]);
  await mockCommercialApi(page);
  await page.route('**/api/v1/enquiry-drafts**', async (route) => {
    const request = route.request();
    if (request.method() === 'GET' && request.url().endsWith('/draft-1')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        id: 'draft-1', version: 0, updatedAt: null, data: {
          branchId: 'br-1', receivedAt: null, clientId: null, clientLabel: null, clientContactId: null,
          operationType: null, expectedDurationHours: null, scopeOfServices: null, specialRequirements: null,
          sourceReference: null, motherVessel: null, motherVesselLabel: null, daughterVessel: null,
          daughterVesselLabel: null, cargo: [], specialItems: [],
        },
      }) });
    }
    if (request.method() === 'DELETE') {
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{"code":"DELETE_FAILED"}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });

  await page.goto('/login');
  await page.getByLabel('Username').fill('commercial');
  await page.locator('#password').fill('commercial');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.goto('/commercial/enquiries/drafts/draft-1');
  await page.getByRole('button', { name: 'Register enquiry' }).click();
  await expect(page.getByText('The enquiry was saved, but its draft could not be removed. Delete the draft before registering it again.')).toBeVisible();
  await expect(page).toHaveURL(/\/commercial\/enquiries\/enq-1$/);
});
