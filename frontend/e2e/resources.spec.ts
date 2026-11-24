import { expect, Page, test } from '@playwright/test';
import { STS_ADMIN, OPS, OPS_MANAGER, mockApi } from './mock-api';
import { mockResourcesApi } from './mock-resources-api';

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible({ timeout: 15_000 });
}

async function chooseDateTime(page: Page, id: string, value: string) {
  const field = page.locator(`#${id}`);
  await field.click();
  await field.fill('');
  await field.pressSequentially(value.replace('T', ' '));
  await page.locator('s2s-date-time-picker').filter({ has: field }).getByRole('button', { name: 'Apply' }).click();
  await expect(field).toHaveValue(value.replace('T', ' '));
}

test('allocation times are applied before submission', async ({ page }) => {
  await mockApi(page, [OPS]);
  const api = await mockResourcesApi(page);
  api.assets.push({ id: 'asset-1', modelId: 'model-1', categoryCode: 'CARGO_HOSE',
    assetIdentifier: 'SYN-HOSE-1', status: 'AVAILABLE', version: 0 });
  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/resources');
  const form = page.locator('form').first();
  await expect(form.locator('select[formControlName="kind"] option')).toHaveCount(2);
  await form.locator('select[formControlName="resourceId"]').selectOption('asset-1');
  await chooseDateTime(page, 'allocation-from', '2028-02-01T05:30');
  await chooseDateTime(page, 'allocation-to', '2028-02-02T05:30');
  await form.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('article.card')).toHaveCount(1);
  expect(api.allocations[0].startAt).toBe('2028-02-01T00:00:00.000Z');
});

test('resource search waits for typing to stop and uses the latest choices', async ({ page }) => {
  await mockApi(page, [OPS]);
  await mockResourcesApi(page);
  const queries: string[] = [];
  await page.route('**/jobs/job-1/resources/allocations/choices?*', async route => {
    const q = new URL(route.request().url()).searchParams.get('q') ?? '';
    queries.push(q);
    await route.fulfill({ json: { assets: q ? [{ id: 'found', identifier: q, category: 'CARGO_HOSE' }] : [],
      people: [], policy: null } });
  });
  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/resources');
  const search = page.getByRole('searchbox', { name: 'Search' });
  await search.fill('SYN');
  await search.fill('SYN-HOSE');
  await expect(page.locator('select[formControlName="resourceId"] option[value="found"]')).toHaveText('SYN-HOSE · CARGO_HOSE');
  expect(queries).not.toContain('SYN');
  expect(queries.at(-1)).toBe('SYN-HOSE');
});

test('master entry and phone allocation collision, exception and second person decision', async ({ page }) => {
  test.setTimeout(90_000);
  await mockApi(page, [STS_ADMIN, OPS, OPS_MANAGER]);
  const api = await mockResourcesApi(page);
  await signIn(page, 'sts.admin', 'sts');
  await page.goto('/administration/equipment');
  await expect(page.getByRole('heading', { name: 'Vendors' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Craft', exact: true })).toHaveCount(0);
  const models = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Equipment models' }) }).first();
  await models.getByLabel('Category').selectOption('CARGO_HOSE');
  await models.getByLabel('Code').fill('SYN-HOSE');
  await models.getByLabel('Name').fill('Synthetic hose model');
  await models.getByRole('button', { name: 'Save' }).click();
  await expect(models).toContainText('Synthetic hose model');
  const assets = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Assets' }) }).first();
  await assets.locator('select[formControlName="modelId"]').selectOption({ label: 'Synthetic hose model' });
  await assets.getByLabel('Asset ID').fill('SYN-HOSE-01');
  await assets.getByRole('button', { name: 'Save' }).click();
  await expect(assets).toContainText('SYN-HOSE-01');
  expect(api.assets).toHaveLength(1);
  await page.getByRole('button', { name: 'Sign out' }).click();

  await signIn(page, 'ops.mumbai', 'ops');
  await page.goto('/jobs/job-1/resources');
  await expect(page.getByRole('heading', { name: 'Resources', exact: true })).toBeVisible();
  const form = page.locator('form').first();
  await form.locator('select[formControlName="resourceId"]').selectOption('asset-2');
  await chooseDateTime(page, 'allocation-from', '2028-02-01T05:30');
  await chooseDateTime(page, 'allocation-to', '2028-02-02T05:30');
  await form.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('article.card')).toHaveCount(1);
  await form.locator('select[formControlName="resourceId"]').selectOption('asset-2');
  await chooseDateTime(page, 'allocation-from', '2028-02-01T06:30');
  await chooseDateTime(page, 'allocation-to', '2028-02-02T06:30');
  await form.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('This resource is already allocated during that interval.')).toBeVisible();
  await form.getByLabel(/Exception reason/).fill('Synthetic conflicting transfer');
  await form.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('article.card')).toHaveCount(2);
  await expect(page.locator('article.card').first()).toContainText('Pending exception');
  await page.getByLabel(/Reason for approval/).fill('Reviewed by a second person');
  await page.locator('article.card').first().getByRole('button', { name: 'Approve exception' }).click();
  await expect(page.getByText(/cannot approve/)).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();

  await signIn(page, 'ops.manager', 'ops');
  await page.goto('/jobs/job-1/resources');
  await page.getByLabel(/Reason for approval/).fill('Synthetic owner decision');
  await page.locator('article.card').first().getByRole('button', { name: 'Approve exception' }).click();
  await expect(page.locator('article.card').first()).toContainText('Active');
  expect(api.allocations.filter(a => a.status === 'ACTIVE')).toHaveLength(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('master table pages, searches and edits on a phone', async ({ page }) => {
  await mockApi(page, [STS_ADMIN]);
  const api=await mockResourcesApi(page);
  for(let i=0;i<28;i++) api.models.push({ id:`seed-${i}`, categoryCode:'CARGO_HOSE',
    code:`SYN-${String(i).padStart(2,'0')}`, name:`Synthetic model ${String(i).padStart(2,'0')}`, active:true, version:0 });
  await signIn(page,'sts.admin','sts');
  await page.goto('/administration/equipment');
  const section=page.locator('section').filter({ has:page.getByRole('heading',{ name:'Equipment models' }) }).first();
  await expect(section.locator('tbody tr')).toHaveCount(25);
  await section.getByRole('button',{ name:'Next Page' }).click();
  await expect(section.locator('tbody tr')).toHaveCount(3);
  await section.getByRole('searchbox',{ name:'Search' }).fill('Synthetic model 27');
  await expect(section.locator('tbody tr')).toHaveCount(1);
  await section.locator('tbody tr').getByRole('button',{ name:'Edit' }).click();
  await section.getByLabel('Name').fill('Synthetic model revised');
  await section.getByRole('button',{ name:'Save' }).click();
  await section.getByRole('searchbox',{ name:'Search' }).fill('revised');
  await expect(section).toContainText('Synthetic model revised');
  await section.getByRole('searchbox',{ name:'Search' }).fill('');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('certificate and inspection corrections show their history on a phone', async ({ page }) => {
  await mockApi(page,[STS_ADMIN]);
  const api=await mockResourcesApi(page);
  api.models.push({ id:'model-1',categoryCode:'CARGO_HOSE',code:'SYN',name:'Synthetic hose',active:true,version:0 });
  api.assets.push({ id:'asset-1',branchId:null,modelId:'model-1',categoryCode:'CARGO_HOSE',
    assetIdentifier:'SYN-HOSE',dimensionsText:null,status:'AVAILABLE',version:0 });
  api.certificates.push({ id:'cert-1',ownerKind:'ASSET',ownerId:'asset-1',typeCode:'HOSE_TEST',
    certificateNumber:'SYN-001',issuer:'Synthetic inspector',issueDate:'2028-01-01',expiryDate:'2029-01-01',
    fileId:null,fileName:null,scanState:null,version:0 });
  api.inspections.push({ id:'inspection-1',assetId:'asset-1',inspectedAt:'2028-01-01',
    result:'PASS',source:'Synthetic source',nextDueAt:null,version:0 });
  await signIn(page,'sts.admin','sts');
  await page.goto('/administration/equipment');
  const assets=page.locator('section').filter({ has:page.getByRole('heading',{name:'Assets'}) }).first();
  await assets.locator('tbody tr').getByRole('button',{name:'Certificates'}).click();
  const certs=page.locator('section').filter({ has:page.getByRole('heading',{name:'Certificates'}) }).first();
  await certs.locator('article.card').getByRole('button',{name:'Edit'}).click();
  await expect(certs).toContainText('Original certificate');
  await certs.getByLabel('Certificate number').fill('SYN-002');
  await certs.getByLabel(/Reason for correction/).fill('Corrected transcription');
  await certs.getByRole('button',{name:'Save'}).click();
  await expect(certs).toContainText('SYN-002');
  const inspections=page.locator('section').filter({ has:page.getByRole('heading',{name:'Inspections'}) }).first();
  await inspections.locator('article.card').getByRole('button',{name:'Edit'}).click();
  await expect(inspections).toContainText('Original inspection');
  await inspections.getByLabel('Source').fill('Corrected synthetic source');
  await inspections.getByLabel(/Reason for correction/).fill('Source typo');
  await inspections.getByRole('button',{name:'Save'}).click();
  await expect(inspections).toContainText('Corrected synthetic source');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('inventory shows original and revised stock, and the units of a model with their job and loss history', async ({ page }) => {
  await mockApi(page, [STS_ADMIN]);
  await mockResourcesApi(page);
  await signIn(page, 'sts.admin', 'sts');
  await page.goto('/operations/inventory');
  await expect(page.getByRole('heading', { name: 'Inventory' })).toBeVisible();
  const row = page.locator('li').filter({ hasText: 'Synthetic fender' }).first();
  await expect(row.locator('div', { hasText: 'Original qty' }).locator('dd')).toHaveText('6');
  await expect(row.locator('div', { hasText: 'Revised qty' }).locator('dd')).toHaveText('3');
  await row.getByRole('button', { name: 'Show units' }).click();
  await expect(row.getByRole('link', { name: 'On job DEMO-JOB-1' })).toBeVisible();
  await expect(row).toContainText('SYN-F-02');
  await expect(row).toContainText('Not found after recovery');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('branch-scoped Operations cannot open company inventory', async ({ page }) => {
  await mockApi(page, [OPS]);
  await signIn(page, 'ops.mumbai', 'ops');
  await expect(page.getByRole('link', { name: 'Inventory' })).toHaveCount(0);
  await page.goto('/operations/inventory');
  await expect(page).toHaveURL(/\/access-denied$/);
});
