import { Page, Route } from '@playwright/test';

/** In-browser stand-in for the JPO and annex endpoints (Phase 3c), with synthetic data only. */
const now = '2026-10-17T05:00:00Z';
const job = { id: 'job-1', jobNumber: 'JOB/2026/00001', status: 'OPEN', branchCode: 'MUM',
  enquiryId: 'enq-1', enquiryReference: 'ENQ/2026/00001', quotationReference: 'QUO/2026/00001',
  client: { id: 'client-1', code: 'DEMO', name: 'DEMO Charterer' }, contact: null, operationType: 'LPG',
  currency: 'USD', quotedAmount: 10000, vessels: [], cargo: [], tariff: null, createdAt: now };
const documents = [
  { id: 'doc-1', jobVesselId: 'jv-1', documentTypeId: 'dt-1', versionNo: 1, fileId: 'file-1', fileName: 'demo-q88.pdf',
    contentType: 'application/pdf', scanState: 'NOT_SCANNED', uploadedAt: now },
  { id: 'doc-2', jobVesselId: 'jv-1', documentTypeId: 'dt-1', versionNo: 1, fileId: 'file-2', fileName: 'demo-mooring.png',
    contentType: 'image/png', scanState: 'NOT_SCANNED', uploadedAt: now },
];
const snapshot = {
  jobNumber: job.jobNumber, operationType: 'LPG',
  vessels: [
    { jobVesselId: 'jv-1', role: 'MOTHER', name: 'DEMO MOTHER', imo: '1000007', etaUtc: null, etaTimezone: null },
    { jobVesselId: 'jv-2', role: 'DAUGHTER', name: 'DEMO DAUGHTER', imo: '1000015', etaUtc: null, etaTimezone: null },
  ],
  cargo: [{ gradeCode: 'PROPANE', gradeName: 'Propane', quantity: '34000', unitCode: 'MT' }],
  voyage: [
    { role: 'MOTHER', fieldCode: 'ARRIVAL_DRAFT_FWD', label: 'Arrival draft forward', unit: 'm', value: '9.85', sourceDocumentId: 'doc-1', sourcePage: 2 },
    { role: 'DAUGHTER', fieldCode: 'ARRIVAL_DRAFT_FWD', label: 'Arrival draft forward', unit: 'm', value: '6.1', sourceDocumentId: null, sourcePage: null },
  ],
  voyageVersionIds: { MOTHER: 'voy-1', DAUGHTER: 'voy-2' },
  checklists: [], warnings: ['Receiving vessel: verified values missing for departure draft fwd.'], takenAt: now,
};
const ancillary = ['Gaskets', 'SS bolts and nuts', 'Rope protectors', 'Quick-release hooks', 'Portable chocks', 'Torque wrench', 'Reducers',
  'Insulation block (LPG only)'].map((item) => ({ item, value: null }));
const annexSections = [
  { code: 'RISK', title: 'Operational risk assessment', body: 'Synthetic annex wording for RISK.', appliesWhen: 'ALWAYS' },
  { code: 'REVERSE', title: 'Reverse lightering', body: 'Synthetic annex wording for REVERSE.', appliesWhen: 'REVERSE_LIGHTERING' },
];

type Json = Record<string, unknown>;

export interface JpoMock { saves: Json[]; acceptances: Json[]; annexSaves: Json[]; annexPublishes: Json[] }

export async function mockJpoApi(page: Page, options: { annexPublished?: boolean; linkedLimits?: boolean; linkedEquipment?: boolean } = {}): Promise<JpoMock> {
  const state: JpoMock = { saves: [], acceptances: [], annexSaves: [], annexPublishes: [] };
  let user = 'technical';
  const revisions: Json[] = [];
  const annex: Json[] = [{ id: 'annex-1', versionNo: 1, status: options.annexPublished ? 'PUBLISHED' : 'DRAFT', name: 'JPO annex (wording pending)',
    sections: annexSections.map((s) => ({ ...s, body: options.annexPublished ? s.body : '' })), ownerConfirmation: null, publishedByName: null, publishedAt: null,
    updatedByName: 'Demo Administrator', updatedAt: now, version: 0 }];
  const published = () => annex.find((a) => a.status === 'PUBLISHED') as Json | undefined;
  const names: Record<string, string> = { technical: 'Demo Technical Officer', marine: 'Demo Marine Superintendent', admin: 'Demo Administrator' };

  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });

  const blockers = (c: Json) => {
    const list: { code: string; message: string }[] = [];
    const equipment = c['equipment'] as Json[];
    if (!c['operationDate']) list.push({ code: 'operationDate', message: 'Enter the date of the operation.' });
    if (!c['mooringDiagramDocumentId']) list.push({ code: 'mooringDiagramDocumentId', message: 'Attach the mooring diagram.' });
    if (!options.linkedLimits && !(c['operatingLimits'] as Json[]).length) list.push({ code: 'operatingLimits', message: 'Enter the agreed operating limits.' });
    if (!c['contingency']) list.push({ code: 'contingency', message: 'Enter the contingency plans.' });
    if (!equipment.some((e) => e['category'] === 'CARGO_HOSE' && Number(e['number'] ?? 0) > 0)) {
      list.push({ code: 'equipment.CARGO_HOSE', message: 'Enter the cargo hoses supplied.' });
    }
    return list;
  };

  const view = (r: Json) => {
    const status = r['status'];
    const actions: string[] = [];
    if (status === 'DRAFT') actions.push('EDIT', 'SUBMIT');
    if (status === 'SUBMITTED') { if (r['submittedBy'] !== user) actions.push('APPROVE'); actions.push('RETURN'); }
    if (status === 'APPROVED') actions.push('ISSUE', 'RETURN');
    if ((status === 'DONE' || status === 'ISSUED') && (r['acceptances'] as Json[]).length < 3) actions.push('ACCEPT');
    const annexVersion = (r['annexVersion'] as Json | undefined) ?? (status === 'DRAFT' ? published() : undefined);
    // 3e: the job's approved operating limits, linked into the JPO.
    const limits = options.linkedLimits ? { operatingLimits: [{ parameterCode: 'WIND_SPEED', parameterLabel: 'Wind speed', unit: 'kn',
      operation: 'Mooring', comparison: 'ABOVE', threshold: 20, action: 'Stop and review' }], operatingLimitsRevision: 1 } : {};
    const allocatedResources = options.linkedEquipment ? [{ kind: 'ASSET', category: 'CARGO_HOSE', identifier: 'HOSE-01', dimensions: '10 in' }] : [];
    return { ...r, snapshot: { ...snapshot, ...limits, allocatedResources }, snapshotFrozen: status !== 'DRAFT', blockers: status === 'DRAFT' ? blockers(r['content'] as Json) : [],
      warnings: snapshot.warnings, sources: { mooringDiagram: 'MANUAL', operatingLimits: options.linkedLimits ? 'LINKED' : 'MANUAL', equipment: options.linkedEquipment ? 'LINKED' : 'MANUAL',
        supportCraft: 'MANUAL' },
      annexTemplateVersion: annexVersion?.['versionNo'] ?? null,
      annexSections: ((annexVersion?.['sections'] as Json[]) ?? []).filter((s) => s['appliesWhen'] === 'ALWAYS'),
      allowedActions: actions, submittedByName: r['submittedBy'] ? names[r['submittedBy'] as string] : null };
  };
  const overview = () => ({ jobId: 'job-1', jobNumber: job.jobNumber,
    revisions: [...revisions].reverse().map((r) => ({ id: r['id'], revisionNo: r['revisionNo'], status: r['status'], issuedAt: r['issuedAt'] })),
    canCreate: !revisions.some((r) => ['DRAFT', 'SUBMITTED', 'APPROVED'].includes(r['status'] as string)) });
  const touch = (r: Json, changes: Json) => Object.assign(r, changes, { version: (r['version'] as number) + 1, updatedAt: now });

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/jobs/job-1' && method === 'GET') return json(route, 200, job);
    if (path === '/jobs/job-1/technical/documents') return json(route, 200, documents);
    if (path === '/jobs/job-1/jpo') return json(route, 200, overview());
    if (path === '/jobs/job-1/jpo/revisions' && method === 'POST') {
      const revision: Json = { id: `rev-${revisions.length + 1}`, revisionNo: revisions.length + 1, status: 'DRAFT',
        content: { operationDate: null, purpose: null, reverseLightering: false, similarLoaBerthing: false, mooringDiagramDocumentId: null,
          contingency: null, operatingLimits: [], cargoRates: [{ cargoGradeCode: 'PROPANE', maxRatePerLine: null, rateUnit: null }],
          equipment: ['CARGO_HOSE', 'VAPOUR_HOSE', 'PRIMARY_FENDER', 'SECONDARY_FENDER'].map((category) =>
            ({ category, number: null, dimensions: null, strings: null })), ancillary },
        acceptances: [], submittedBy: null, submittedAt: null, decidedByName: null, decidedAt: null, returnedReason: null,
        issuedByName: null, issuedAt: null, pdfSha256: null, updatedAt: now, version: 0 };
      revisions.push(revision);
      return json(route, 201, view(revision));
    }
    const match = path.match(/^\/jobs\/job-1\/jpo\/revisions\/([^/]+)(?:\/(\w+))?$/);
    const revision = match && revisions.find((r) => r['id'] === match[1]);
    if (!match || !revision) return route.fallback();
    const action = match[2];
    if (!action && method === 'GET') return json(route, 200, view(revision));
    if (!action && method === 'PUT') {
      const content = route.request().postDataJSON() as Json;
      state.saves.push(content);
      return json(route, 200, view(touch(revision, { content })));
    }
    if (action === 'submit') {
      if (blockers(revision['content'] as Json).length) return json(route, 400, { status: 400, code: 'JPO_INCOMPLETE' });
      return json(route, 200, view(touch(revision, { status: 'DONE', submittedBy: user, submittedAt: now, issuedByName: names[user], issuedAt: now,
        pdfSha256: 'a'.repeat(64), annexVersion: published() })));
    }
    if (action === 'decision') {
      const body = route.request().postDataJSON() as { decision: string; reason: string | null };
      if (body.decision === 'APPROVE') {
        if (revision['submittedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
        return json(route, 200, view(touch(revision, { status: 'APPROVED', decidedByName: names[user], decidedAt: now })));
      }
      return json(route, 200, view(touch(revision, { status: 'DRAFT', returnedReason: body.reason, annexVersion: undefined })));
    }
    if (action === 'issue') {
      return json(route, 200, view(touch(revision, { status: 'ISSUED', issuedByName: names[user], issuedAt: now, pdfSha256: 'a'.repeat(64) })));
    }
    if (action === 'acceptances') {
      const body = route.request().postDataJSON() as Json;
      state.acceptances.push(body);
      (revision['acceptances'] as Json[]).push({ ...body, recordedByName: names[user], recordedAt: now });
      return json(route, 200, view(touch(revision, {})));
    }
    return route.fallback();
  });

  await page.route('**/api/v1/technical/annex-templates**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/technical/annex-templates' && method === 'GET') return json(route, 200, [...annex].reverse());
    const match = path.match(/^\/technical\/annex-templates\/([^/]+)(\/publish)?$/);
    const template = match && annex.find((a) => a['id'] === match[1]);
    if (!template) return route.fallback();
    if (match![2]) {
      const body = route.request().postDataJSON() as Json;
      state.annexPublishes.push(body);
      if (template['updatedByName'] === names[user]) return json(route, 422, { status: 422, code: 'SECOND_PERSON_REQUIRED' });
      return json(route, 200, touch(template, { status: 'PUBLISHED', ownerConfirmation: body['ownerConfirmation'],
        publishedByName: names[user], publishedAt: now }));
    }
    if (method === 'PUT') {
      const body = route.request().postDataJSON() as Json;
      state.annexSaves.push(body);
      return json(route, 200, touch(template, { ...body, updatedByName: names[user] }));
    }
    return json(route, 200, template);
  });

  return state;
}
