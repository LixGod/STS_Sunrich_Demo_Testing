import { Page, Route } from '@playwright/test';

const now = '2026-10-17T05:00:00Z';
const vessels = [
  { id: 'jv-1', role: 'MOTHER', vesselId: 'v-1', name: 'DEMO MOTHER', imo: '1000007', etaUtc: null, etaTimezone: null },
  { id: 'jv-2', role: 'DAUGHTER', vesselId: 'v-2', name: 'DEMO DAUGHTER', imo: '1000015', etaUtc: null, etaTimezone: null },
];
const job = { id: 'job-1', jobNumber: 'JOB/2026/00001', status: 'OPEN', 
  branchCode: 'MUM', enquiryId: 'enq-1', enquiryReference: 'ENQ/2026/00001', quotationReference: 'QUO/2026/00001',
  client: { id: 'client-1', code: 'DEMO', name: 'DEMO Charterer' }, contact: null, operationType: null,
  currency: 'USD', quotedAmount: 10000, vessels, cargo: [], tariff: null, createdAt: now };

export interface JobTechnicalMock { uploads: number; saves: number; verified: number; gaLinks: number }

export async function mockJobTechnicalApi(page: Page, options: { masterGa?: boolean; receivedButUnreviewed?: boolean } = {}): Promise<JobTechnicalMock> {
  const state: JobTechnicalMock = { uploads: 0, saves: 0, verified: 0, gaLinks: 0 };
  let user = 'technical';
  const documents: Record<string, unknown>[] = [];
  if (options.masterGa) documents.push({ id: 'doc-ga-1', jobVesselId: 'jv-1', documentTypeId: 'dt-ga', versionNo: 1,
    fileId: 'file-ga-1', fileName: 'mother-ga.pdf', contentType: 'application/pdf', scanState: 'NOT_SCANNED', uploadedAt: now });
  const requestItems: Record<string, unknown>[] = [
    { id: 'item-1', jobVesselId: 'jv-1', code: 'Q88', label: 'Q88 questionnaire', kind: 'DOCUMENT', documentTypeId: 'dt-1',
      required: true, status: 'PENDING', responseText: null, reviewComment: null, documentVersionIds: [], events: [], version: 0 },
    { id: 'item-2', jobVesselId: 'jv-1', code: 'REDUCER', label: '10-inch reducer availability', kind: 'DECLARATION',
      documentTypeId: null, required: true, status: 'PENDING', responseText: null, reviewComment: null, documentVersionIds: [], events: [], version: 0 },
    { id: 'item-ga-1', jobVesselId: 'jv-1', code: 'GA', label: 'General arrangement plan', kind: 'DOCUMENT',
      documentTypeId: 'dt-ga', required: true, status: 'PENDING', responseText: null, reviewComment: null, documentVersionIds: [], events: [], version: 0 },
    { id: 'item-ga-2', jobVesselId: 'jv-2', code: 'GA', label: 'General arrangement plan', kind: 'DOCUMENT',
      documentTypeId: 'dt-ga', required: true, status: 'REVIEWED', responseText: null, reviewComment: null, documentVersionIds: ['doc-ga-2'], events: [], version: 1 },
  ];
  if (options.receivedButUnreviewed) requestItems.forEach(item => {
    if (item['status'] === 'PENDING') item['status'] = 'RECEIVED';
  });
  let voyage: Record<string, unknown> | null = null;
  const checklistTemplates: Record<string, unknown>[] = [];
  const requestTemplates: Record<string, unknown>[] = [{ id: 'template-1', versionNo: 1, status: 'DRAFT',
    name: 'Step 4 candidate request', emailSubject: 'Document request for {JOB_NUMBER}', emailBody: '{REQUEST_ITEMS}',
    items: [{ code: 'Q88', label: 'Q88 questionnaire', kind: 'DOCUMENT', appliesTo: 'EACH_VESSEL',
      documentTypeId: 'dt-1', required: true }], version: 0 }];
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const request = () => ({ id: 'request-1', jobId: 'job-1', status: 'ACTIVE', templateVersion: 2, items: requestItems, version: 0 });
  const overview = () => ({ jobVesselId: 'jv-1', versions: voyage ? [voyage] : [] });
  const review = () => ({ jobId: 'job-1', warnings: voyage?.['status'] === 'VERIFIED'
    ? [{ code: 'MISSING_VERIFIED_VOYAGE', vesselRole: 'DAUGHTER', message: 'No verified voyage-condition values are available.' }]
    : [{ code: 'MISSING_VERIFIED_VOYAGE', vesselRole: 'MOTHER', message: 'No verified voyage-condition values are available.' },
      { code: 'MISSING_VERIFIED_VOYAGE', vesselRole: 'DAUGHTER', message: 'No verified voyage-condition values are available.' }],
  fixedParticularsVersionIds: { MOTHER: null, DAUGHTER: null },
  voyageVersionIds: { MOTHER: voyage?.['status'] === 'VERIFIED' ? 'voy-1' : null, DAUGHTER: null } });

  await page.route('**/api/v1/auth/login', async route => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });
  await page.route('**/api/v1/jobs/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/jobs/job-1' && method === 'GET') return json(route, 200, job);
    if (path === '/jobs/job-1/technical/request') {
      if (method === 'POST' && options.masterGa) {
        state.gaLinks++;
        requestItems[2]['status'] = 'RECEIVED'; requestItems[2]['documentVersionIds'] = ['doc-ga-1']; requestItems[2]['version'] = 1;
      }
      return json(route, 200, request());
    }
    if (path === '/jobs/job-1/technical/documents' && method === 'GET') return json(route, 200, documents);
    if (path === '/jobs/job-1/technical/documents' && method === 'POST') {
      state.uploads++;
      const document = { id: 'doc-1', jobVesselId: 'jv-1', documentTypeId: 'dt-1', versionNo: 1,
        fileId: 'file-1', fileName: 'demo-q88.pdf', contentType: 'application/pdf', scanState: 'NOT_SCANNED', uploadedAt: now };
      documents.unshift(document);
      requestItems[0]['status'] = 'RECEIVED'; requestItems[0]['documentVersionIds'] = ['doc-1']; requestItems[0]['version'] = 1;
      voyage = { id: 'voy-1', jobVesselId: 'jv-1', versionNo: 1, status: 'VERIFIED', notes: 'Taken from demo-q88.pdf',
        values: [{ fieldCode: 'ARRIVAL_DRAFT_FWD', value: '11.5', sourceDocumentId: 'doc-1', sourcePage: 1, verified: true }],
        submittedBy: null, submittedAt: null, verifiedBy: `user-${user}`, verifiedAt: now, returnedReason: null, version: 1 };
      return json(route, 201, document);
    }
    if (path === '/jobs/job-1/technical/vessels/jv-1/voyage/from-document' && method === 'POST') {
      voyage = { id: 'voy-1', jobVesselId: 'jv-1', versionNo: 1, status: 'VERIFIED', notes: 'Taken from document',
        values: [{ fieldCode: 'ARRIVAL_DRAFT_FWD', value: '11.5', sourceDocumentId: 'doc-1', sourcePage: 1, verified: true }],
        submittedBy: null, submittedAt: null, verifiedBy: `user-${user}`, verifiedAt: now, returnedReason: null, version: 1 };
      return json(route, 200, voyage);
    }
    if (path === '/jobs/job-1/technical/vessels/jv-1/voyage/from-document' && method === 'PUT') {
      state.saves++; state.verified++;
      const body = route.request().postDataJSON() as { values: Record<string, unknown>[] };
      voyage = { ...(voyage ?? { id: 'voy-1', jobVesselId: 'jv-1', versionNo: 1 }), status: 'VERIFIED',
        values: body.values.map(v => ({ ...v, verified: true })), verifiedBy: `user-${user}`, verifiedAt: now, version: 2 };
      return json(route, 200, voyage);
    }
    if (path === '/jobs/job-1/technical/request/email-preview') return json(route, 200, {
      subject: 'DEMO request JOB/2026/00001', body: 'Please send the Q88. [radius pending]',
      unresolved: ['RADIUS_NM'], templateVersion: 2,
    });
    const itemChange = path.match(/^\/jobs\/job-1\/technical\/request\/items\/(.+)$/);
    if (itemChange && method === 'PATCH') {
      const body = route.request().postDataJSON() as { status: string; responseText: string };
      const item = requestItems.find(entry => entry['id'] === itemChange[1]);
      if (!item) return json(route, 404, { status: 404, code: 'NOT_FOUND' });
      item['status'] = body.status; item['responseText'] = body.responseText;
      return json(route, 200, request());
    }
    if (path === '/jobs/job-1/technical/voyage-fields') return json(route, 200, [
      { code: 'ARRIVAL_DRAFT_FWD', label: 'Arrival draft forward', unit: 'm', requiredForReview: false },
    ]);
    if (path === '/jobs/job-1/technical/vessels/jv-1/voyage' && method === 'GET') return json(route, 200, overview());
    if (path === '/jobs/job-1/technical/vessels/jv-2/voyage' && method === 'GET') return json(route, 200, { jobVesselId: 'jv-2', versions: [] });
    if (path === '/jobs/job-1/technical/vessels/jv-1/voyage' && method === 'POST') {
      voyage = { id: 'voy-1', jobVesselId: 'jv-1', versionNo: 1, status: 'DRAFT', notes: null, values: [],
        submittedBy: null, submittedAt: null, verifiedBy: null, verifiedAt: null, returnedReason: null, version: 0 };
      return json(route, 201, voyage);
    }
    if (path === '/jobs/job-1/technical/voyage/voy-1' && method === 'PUT') {
      state.saves++;
      const body = route.request().postDataJSON() as { values: Record<string, unknown>[] };
      voyage = { ...voyage, values: body.values.map(v => ({ ...v, verified: false })), version: 1 };
      return json(route, 200, voyage);
    }
    if (path === '/jobs/job-1/technical/voyage/voy-1/submit' && method === 'POST') {
      voyage = { ...voyage, status: 'SUBMITTED', submittedBy: `user-${user}`, submittedAt: now, version: 2 };
      return json(route, 200, voyage);
    }
    if (path === '/jobs/job-1/technical/voyage/voy-1/verify' && method === 'POST') {
      if (user === 'technical') return json(route, 422, { status: 422, code: 'SELF_VERIFICATION_FORBIDDEN' });
      state.verified++;
      voyage = { ...voyage, status: 'VERIFIED', verifiedBy: `user-${user}`, verifiedAt: now, version: 3,
        values: (voyage?.['values'] as Record<string, unknown>[]).map(v => ({ ...v, verified: true })) };
      return json(route, 200, voyage);
    }
    if (path === '/jobs/job-1/technical/review') return json(route, 200, review());
    if (path === '/jobs/job-1/technical/checklists') return json(route, 200, []);
    return route.fallback();
  });
  await page.route('**/api/v1/technical/checklist-templates**', route => {
    const method = route.request().method();
    const path = new URL(route.request().url()).pathname;
    if (method === 'GET') return json(route, 200, checklistTemplates);
    if (method === 'POST' && path.endsWith('/publish')) {
      checklistTemplates[0]['status'] = 'PUBLISHED'; checklistTemplates[0]['version'] = 1;
      return json(route, 200, checklistTemplates[0]);
    }
    if (method === 'POST') {
      const input = route.request().postDataJSON() as Record<string, unknown>;
      const created = { ...input, id: 'checklist-template-1', versionNo: 1, status: 'DRAFT', version: 0 };
      checklistTemplates.push(created);
      return json(route, 201, created);
    }
    return json(route, 404, { status: 404, code: 'NOT_FOUND' });
  });
  await page.route('**/api/v1/technical/request-templates**', route => {
    if (route.request().method() === 'GET') return json(route, 200, requestTemplates);
    return json(route, 404, { status: 404, code: 'NOT_FOUND' });
  });
  await page.route('**/api/v1/masters/document-types**', route => json(route, 200, {
    items: [{ id: 'dt-1', code: 'Q88', name: 'Q88 questionnaire', appliesTo: 'VESSEL', required: true, active: true },
      { id: 'dt-ga', code: 'GA-PLAN', name: 'General arrangement plan', appliesTo: 'VESSEL', required: true, active: true }],
    page: 0, size: 100, totalElements: 2,
  }));
  return state;
}
