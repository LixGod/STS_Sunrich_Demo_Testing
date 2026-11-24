import { Page, expect, test } from '@playwright/test';
import { MARINE, TECHNICAL, mockApi } from './mock-api';
import { mockMooringPlanApi } from './mock-mooring-plan-api';

async function signIn(page: Page, user: 'technical' | 'marine') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(user);
  await page.locator('#password').fill(user);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}
async function signOut(page: Page) { await page.getByRole('button', { name: 'Sign out' }).click(); }
async function noScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
}

test('a complete plan can be built with forms, checked by a second person and issued', async ({ page }) => {
  test.setTimeout(180_000);
  await mockApi(page, [TECHNICAL, MARINE]);
  const api = await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  await expect(page.locator('.blockers')).toContainText('Choose vessel heading roles.');

  const vessels = page.locator('#mooring-vessels');
  const advanced = page.locator('details.advanced');
  await advanced.locator('summary').click();
  await advanced.getByLabel('Ruler step (m)').fill('8');
  await advanced.locator('form').first().getByRole('button', { name: 'Apply to draft' }).click();
  const vesselForm = vessels.locator('form').first();
  await vesselForm.getByLabel('Heading designation').selectOption('CONSTANT');
  await vesselForm.getByLabel('Lateral offset from manifold (m)').fill('20');
  await vesselForm.getByRole('button', { name: 'Apply to draft' }).click();
  await vesselForm.getByLabel('Vessel').selectOption('jv-2');
  await vesselForm.getByLabel('Heading designation').selectOption('MANOEUVRING');
  await vesselForm.getByLabel('Lateral offset from manifold (m)').fill('-20');
  await vesselForm.getByRole('button', { name: 'Apply to draft' }).click();

  const particularForm = advanced.locator('form').nth(1);
  for (const roleIndex of [0, 1]) for (const code of ['PRIMARY_FENDER_SIZE', 'OIL_BOOM_LENGTH']) {
    await particularForm.getByLabel('Vessel').selectOption(`jv-${roleIndex + 1}`);
    await particularForm.getByLabel('Field').selectOption(code);
    await particularForm.getByLabel('Value (m)').fill('10');
    await particularForm.getByLabel('Source description').fill('Synthetic certified source');
    await particularForm.getByRole('button', { name: 'Apply to draft' }).click();
  }

  await page.locator('.step-nav button').nth(1).click();
  const objects = page.locator('#mooring-lines-step');
  await objects.getByRole('button', { name: 'Add object' }).click();
  const objectForm = objects.locator('form');
  await objectForm.getByLabel('Label').fill('Point A');
  await objectForm.getByLabel('X from manifold (m)').fill('-10');
  await objectForm.getByLabel('Y from manifold (m)').fill('20');
  await objectForm.getByRole('button', { name: 'Apply to draft' }).click();
  await objects.getByRole('button', { name: 'Add object' }).click();
  await objectForm.getByLabel('Label').fill('Point B');
  await objectForm.getByLabel('X from manifold (m)').fill('10');
  await objectForm.getByLabel('Y from manifold (m)').fill('-20');
  await objectForm.getByRole('button', { name: 'Apply to draft' }).click();

  const lines = objects;
  await lines.getByRole('button', { name: 'Add line' }).click();
  const lineForm = lines.locator('form').first();
  await lineForm.getByLabel('Line ID').fill('F1');
  await lineForm.getByLabel('From point').selectOption({ label: 'Point A' });
  await lineForm.getByLabel('To point').selectOption({ label: 'Point B' });
  await lineForm.getByRole('button', { name: 'Apply to draft' }).click();
  const capacityForm = advanced.locator('form').nth(2);
  for (const name of ['Ops BHC', 'BiGs MBL']) {
    await advanced.locator('.capacity-row').getByRole('button', { name: new RegExp(name) }).click();
    await capacityForm.getByLabel('Value', { exact: true }).fill('20');
    await capacityForm.getByLabel('Source description').fill('Synthetic certified source');
    await capacityForm.getByRole('button', { name: 'Apply to draft' }).click();
  }
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => api.saves.length).toBe(1);
  expect(api.saves[0].objects).toHaveLength(2);
  expect(api.saves[0].lines[0].label).toBe('F1');
  await noScroll(page);

  await signOut(page);
  await signIn(page, 'marine');
  await page.goto('/jobs/job-1/mooring-plan');
  await advanced.locator('summary').click();
  for (const roleIndex of [0, 1]) for (const code of ['PRIMARY_FENDER_SIZE', 'OIL_BOOM_LENGTH']) {
    await particularForm.getByLabel('Vessel').selectOption(`jv-${roleIndex + 1}`);
    await particularForm.getByLabel('Field').selectOption(code);
    await particularForm.getByRole('button', { name: 'Verify against source' }).click();
  }
  for (const name of ['Ops BHC', 'BiGs MBL']) {
    await advanced.locator('.capacity-row').getByRole('button', { name: new RegExp(name) }).click();
    await capacityForm.getByRole('button', { name: 'Verify against source' }).click();
  }
  await expect(page.locator('.blockers')).toHaveCount(0);
  await noScroll(page);

  await signOut(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.locator('.summary s2s-status-tag')).toContainText('Submitted');
  await signOut(page);
  await signIn(page, 'marine');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await page.getByRole('button', { name: 'Issue PDF' }).click();
  await expect(page.locator('.summary s2s-status-tag')).toContainText('Issued');
  await expect(page.getByRole('link', { name: 'Download PDF' }).first()).toHaveAttribute('href', /mooring-plan\/revisions\/moor-1\/pdf$/);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
    Object.defineProperty(navigator, 'share', { configurable: true, value: async (data: ShareData) => {
      (window as Window & { sharedPlan?: string }).sharedPlan = data.files?.[0]?.name;
    } });
  });
  await page.getByRole('button', { name: 'Share issued plan' }).click();
  await expect.poll(() => page.evaluate(() => (window as Window & { sharedPlan?: string }).sharedPlan))
    .toBe('JOB-2026-00001-Mooring-r1.pdf');
  await noScroll(page);
});

test('moving an object on desktop updates the editable model', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name.startsWith('mobile-'));
  await mockApi(page, [TECHNICAL]);
  const api = await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  await page.locator('.step-nav button').nth(1).click();
  await page.getByRole('button', { name: 'Add object' }).click();
  const form = page.locator('#mooring-lines-step form');
  await form.getByLabel('Label').fill('Drag me');
  await form.getByRole('button', { name: 'Apply to draft' }).click();
  const object = page.locator('svg g[role="button"] circle').first();
  const box = await object.boundingBox();
  expect(box).not.toBeNull();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x + box!.width / 2 + 35, box!.y + box!.height / 2 + 15);
  await page.mouse.up();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => api.saves.length).toBe(1);
  expect(api.saves[0].objects[0].xM).not.toBe(0);
});

test('selecting a drawing object opens its form without changing the saved plan', async ({ page }, testInfo) => {
  await mockApi(page, [TECHNICAL]);
  const api = await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  await page.locator('.step-nav button').nth(1).click();
  await page.getByRole('button', { name: 'Add object' }).click();
  await page.locator('#mooring-lines-step').getByLabel('Label').fill('Point A');
  await page.locator('#mooring-lines-step').getByRole('button', { name: 'Apply to draft' }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => api.saves.length).toBe(1);

  const object = page.locator('s2s-mooring-plan-canvas').getByRole('button', { name: 'Point A' });
  await page.locator('.step-nav button').first().click();
  if (testInfo.project.name.startsWith('mobile-')) await object.locator('circle.symbol').tap();
  else await object.locator('circle.symbol').click();
  await expect(page.locator('#mooring-lines-step').getByLabel('Label')).toHaveValue('Point A');
  await expect(page.locator('#mooring-object-editor')).toBeInViewport();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await page.locator('.step-nav button').first().click();
  await object.focus();
  await object.press('Enter');
  await expect(page.locator('#mooring-lines-step').getByLabel('Label')).toHaveValue('Point A');
  await page.locator('#mooring-lines-step').getByLabel('Label').fill('Unapplied label');
  await object.press('Enter');
  await expect(page.locator('#mooring-lines-step').getByLabel('Label')).toHaveValue('Unapplied label');
  expect(api.saves).toHaveLength(1);
});

test('switching revisions asks before discarding a changed plan', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  const api = await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  api.revisions.push({ ...structuredClone(api.revisions[0]!), id: 'moor-2', revisionNo: 2, status: 'ISSUED' });
  await page.reload();
  await page.locator('.revisions button').nth(1).click();
  await page.locator('.step-nav button').nth(1).click();
  await page.getByRole('button', { name: 'Add object' }).click();
  await page.locator('#mooring-lines-step').getByLabel('Label').fill('Draft point');
  await page.locator('#mooring-lines-step').getByRole('button', { name: 'Apply to draft' }).click();
  await page.locator('#mooring-lines-step').getByLabel('Label').fill('Unapplied point');
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();

  page.once('dialog', dialog => dialog.dismiss());
  await page.locator('.revisions button').first().click();
  await expect(page.locator('.summary h2')).toContainText('1');
  await expect(page.locator('#mooring-lines-step').getByLabel('Label')).toHaveValue('Unapplied point');
  await page.locator('#mooring-lines-step').getByRole('button', { name: 'Apply to draft' }).click();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('.revisions button').first().click();
  await expect(page.locator('.summary h2')).toContainText('2');
});

test('switching mooring lines asks before discarding unapplied edits', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  await page.locator('.step-nav button').nth(1).click();
  const lines = page.locator('#mooring-lines-step');
  await lines.getByRole('button', { name: 'Add line' }).click();
  const form = lines.locator('form').first();
  await form.getByLabel('Line ID').fill('F1');
  await form.getByRole('button', { name: 'Apply to draft' }).click();
  await lines.getByRole('button', { name: 'Add line' }).click();
  await form.getByLabel('Line ID').fill('Unsaved F2');

  page.once('dialog', dialog => dialog.dismiss());
  await lines.getByRole('button', { name: /F1 · Berthing/ }).click();
  await expect(form.getByLabel('Line ID')).toHaveValue('Unsaved F2');
  page.once('dialog', dialog => dialog.accept());
  await lines.getByRole('button', { name: /F1 · Berthing/ }).click();
  await expect(form.getByLabel('Line ID')).toHaveValue('F1');
});

test('a slow revision response cannot replace the latest selection', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  const api = await mockMooringPlanApi(page);
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  api.revisions.push({ ...structuredClone(api.revisions[0]!), id: 'moor-2', revisionNo: 2, status: 'ISSUED' });
  await page.reload();
  await page.route('**/jobs/job-1/mooring-plan/revisions/moor-1', async route => {
    await new Promise(resolve => setTimeout(resolve, 300));
    await route.fallback();
  });

  await page.locator('.revisions button').nth(1).click();
  await page.locator('.revisions button').first().click();
  await expect(page.locator('.summary h2')).toContainText('2');
  await page.waitForTimeout(400);
  await expect(page.locator('.summary h2')).toContainText('2');
});

test('reference layers load bounded previews instead of original images', async ({ page }) => {
  await mockApi(page, [TECHNICAL]);
  const api = await mockMooringPlanApi(page);
  await page.route('**/api/v1/jobs/job-1/technical/documents', route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify([{ id: 'doc-1', jobVesselId: 'jv-1', documentTypeId: 'ga',
      versionNo: 1, fileId: 'file-1', fileName: 'GA.png', contentType: 'image/png', scanState: 'CLEAN', uploadedAt: '2026-10-18T05:00:00Z' }]) }));
  await page.route('**/api/v1/files/file-1/download-token', route => route.fulfill({ status: 200,
    contentType: 'application/json', body: JSON.stringify({ url: '/api/v1/files/download/preview-token' }) }));
  let previews = 0;
  await page.route('**/api/v1/files/download/preview-token/preview', route => {
    previews++;
    return route.fulfill({ status: 200, contentType: 'image/png',
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64') });
  });
  await signIn(page, 'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button', { name: 'Prepare mooring plan' }).click();
  api.revisions[0]!.model.layers.push({ id: 'layer-1', jobVesselId: 'jv-1', documentVersionId: 'doc-1',
    xM: 0, yM: 0, scale: 1, visible: true });
  await page.reload();

  await expect(page.locator('s2s-mooring-plan-canvas image')).toHaveAttribute('href', '/api/v1/files/download/preview-token/preview');
  await expect.poll(() => previews).toBeGreaterThan(0);
});

test('a plan with 200 objects remains editable on a phone', async ({ page }) => {
  test.setTimeout(60_000);
  await mockApi(page,[TECHNICAL]);
  const api=await mockMooringPlanApi(page);
  await signIn(page,'technical');
  await page.goto('/jobs/job-1/mooring-plan');
  await page.getByRole('button',{name:'Prepare mooring plan'}).click();
  api.revisions[0]!.model.objects=Array.from({length:200},(_,i)=>({
    id:`point-${i}`,type:'MOORING_POINT',xM:i-100,yM:0,jobVesselId:null,label:`Point ${i}`,
  }));
  await page.reload();
  await page.locator('.step-nav button').nth(1).click();
  const objects=page.locator('#mooring-lines-step');
  const objectList = objects.locator('.item-list').nth(1);
  await expect(objectList.locator('button')).toHaveCount(200);
  await objectList.locator('button').first().click();
  await objects.getByLabel('X from manifold (m)').fill('-99');
  await objects.getByRole('button',{name:'Apply to draft'}).click();
  await page.getByRole('button',{name:'Save',exact:true}).click();
  await expect.poll(()=>api.saves.length).toBe(1);
  expect(api.saves[0]!.objects).toHaveLength(200);
  expect(api.saves[0]!.objects[0]!.xM).toBe(-99);
  await noScroll(page);
});
