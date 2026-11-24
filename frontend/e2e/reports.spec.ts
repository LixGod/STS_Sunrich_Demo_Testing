import { Page, expect, test } from '@playwright/test';
import { APPROVER, mockApi } from './mock-api';

const MANAGER = { ...APPROVER, permissions: [...APPROVER.permissions, 'REPORT.MANAGEMENT.READ'] };

async function signIn(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Username').fill('approver');
  await page.locator('#password').fill('approver');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

async function choose(page: Page, inputId: string, option: string) {
  await page.locator(`#${inputId}`).click();
  await page.getByRole('option', { name: option, exact: true }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

const json = (body: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

test('document register lists generated documents, filters by type and opens the source screen', async ({ page }) => {
  await mockApi(page, [MANAGER]);
  const queries: URLSearchParams[] = [];
  await page.route('**/api/v1/reports/documents**', (route) => {
    const url = new URL(route.request().url());
    queries.push(url.searchParams);
    const rows = [
      { kind: 'QUOTATION', id: 'qv-1', number: 'QUO/2026/00001', revision: 1, status: 'ACCEPTED', jobId: 'job-1', jobNumber: 'JOB/2026/00001',
        enquiryId: 'enq-1', issuedAt: '2026-10-18T06:00:00Z', issuedBy: 'Demo Commercial Executive', updatedAt: '2026-10-18T06:00:00Z', pdfSha256: 'a'.repeat(64) },
      { kind: 'FINAL_REPORT', id: 'fr-1', number: null, revision: 2, status: 'DRAFT', jobId: 'job-1', jobNumber: 'JOB/2026/00001',
        enquiryId: 'enq-1', issuedAt: null, issuedBy: null, updatedAt: '2026-10-19T06:00:00Z', pdfSha256: null },
    ].filter((r) => !url.searchParams.get('kind') || r.kind === url.searchParams.get('kind'));
    return route.fulfill(json({ items: rows, page: 0, size: 25, totalElements: rows.length }));
  });
  await signIn(page);
  await page.goto('/reports/documents');
  await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible();
  await expect(page.getByText('QUO/2026/00001')).toBeVisible();
  await expect(page.getByText('Issued by Demo Commercial Executive', { exact: false })).toBeVisible();
  await expect(page.getByText('Revision 2')).toBeVisible();

  await choose(page, 'dr-kind', 'Quotation');
  await expect(page.getByText('Revision 2')).toHaveCount(0);
  expect(queries.at(-1)?.get('kind')).toBe('QUOTATION');
  await expect(page.getByRole('link', { name: 'Quotation' })).toHaveAttribute('href', '/commercial/enquiries/enq-1/quotation');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('job history groups jobs, refilters from dropdowns and exports the same view', async ({ page }) => {
  await mockApi(page, [MANAGER]);
  const queries: URLSearchParams[] = [];
  await page.route('**/api/v1/reports/job-history/options', (route) => route.fulfill(json({
    clients: [{ id: 'c-1', name: 'DEMO Charterers Ltd (Synthetic)' }], vessels: [], cargoGrades: [{ id: 'g-1', name: 'Propane' }], locations: [],
  })));
  await page.route('**/api/v1/reports/job-history?**', (route) => {
    const url = new URL(route.request().url());
    queries.push(url.searchParams);
    const byCargo = url.searchParams.get('groupBy') === 'CARGO';
    return route.fulfill(json({
      groupBy: url.searchParams.get('groupBy'), capped: false,
      groups: [{ key: 'k', label: byCargo ? 'Propane' : 'DEMO Charterers Ltd (Synthetic)', jobs: 2, cargo: [{ grade: 'Propane', unit: 'MT', quantity: 68000 }] }],
      jobs: { page: 0, size: 25, totalElements: 1, items: [{ jobId: 'job-1', jobNumber: 'JOB/2026/00001', status: 'OPEN', jobDate: '2026-10-18',
        client: 'DEMO Charterers Ltd (Synthetic)', motherVessel: 'DEMO MOTHER', daughterVessel: 'DEMO DAUGHTER', location: 'DEMO-A1 — Demo Anchorage A1',
        cargo: [{ grade: 'Propane', unit: 'MT', quantity: 34000 }] }] },
    }));
  });
  await signIn(page);
  await page.goto('/reports/job-history');
  await expect(page.getByRole('heading', { name: 'Job history' })).toBeVisible();
  await expect(page.getByText('Summary by Client')).toBeVisible();
  await expect(page.getByText('2 job(s)')).toBeVisible();
  await expect(page.getByText('Propane 68,000 MT')).toBeVisible();

  await choose(page, 'jh-group', 'Cargo grade');
  await expect(page.getByText('Summary by Cargo grade')).toBeVisible();
  expect(queries.at(-1)?.get('groupBy')).toBe('CARGO');
  await choose(page, 'jh-grade', 'Propane');
  expect(queries.at(-1)?.get('cargoGradeId')).toBe('g-1');
  await expect(page.getByRole('link', { name: /Excel/ })).toHaveAttribute('href', /groupBy=CARGO.*|cargoGradeId=g-1/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
});

test('the reports menu offers only what the user may open', async ({ page }) => {
  await mockApi(page, [APPROVER]);
  await signIn(page);
  const toggle = page.getByRole('button', { name: 'Show or hide the menu' });
  if (await toggle.isVisible()) await toggle.click();
  const nav = page.getByRole('navigation', { name: 'Main navigation' });
  await nav.getByText('Reports & History').click();
  await expect(nav.getByRole('link', { name: 'Documents' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Job history' })).toHaveCount(0);
  await page.goto('/reports/job-history');
  await expect(page.getByText('You do not have permission to open this page.')).toBeVisible();
});
