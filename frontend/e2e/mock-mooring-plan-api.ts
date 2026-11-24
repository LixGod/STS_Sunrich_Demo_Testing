import { Page, Route } from '@playwright/test';
import { MooringModel, MooringRevision, MooringSnapshot } from '../src/app/features/technical/mooring-plan.service';

const now = '2026-10-18T05:00:00Z';
const dimensions = ['LOA', 'BEAM', 'BOW_TO_MANIFOLD', 'STERN_TO_MANIFOLD', 'ACCOMMODATION_TO_MANIFOLD'];
const vessels = [
  { jobVesselId: 'jv-1', role: 'MOTHER', name: 'DEMO MOTHER', imo: '1000007' },
  { jobVesselId: 'jv-2', role: 'DAUGHTER', name: 'DEMO DAUGHTER', imo: '1000015' },
];
const columns = ['OPS_BHC', 'BIGS_MBL'].map((code, i) => ({ code, label: i ? 'BiGs MBL' : 'Ops BHC',
  definition: 'Synthetic test capacity', unit: 't', evidenceRequirement: 'Synthetic source', status: 'ACTIVE', version: 1 }));

export interface MooringMock { saves: MooringModel[]; revisions: MooringRevision[] }

export async function mockMooringPlanApi(page: Page): Promise<MooringMock> {
  const state: MooringMock = { saves: [], revisions: [] };
  let user = 'technical';
  const verified = new Set<string>();
  const snapshot = (): MooringSnapshot => ({
    vessels: vessels.map(v => ({ ...v, dimensions: dimensions.map(code => ({ jobVesselId: v.jobVesselId, code,
      value: code === 'LOA' ? 150 : code === 'BEAM' ? 30 : code === 'ACCOMMODATION_TO_MANIFOLD' ? 40 : 75,
      unit: 'm', particularsVersionId: `ver-${v.jobVesselId}`, sourceDocumentId: null, sourcePage: 1, verified: true })) })),
    capacityColumns: columns, verifiedValues: [...verified].map(key => ({ key, verifiedBy: 'user-marine', verifiedAt: now })), takenAt: now,
  });
  const json = (route: Route, status: number, body: unknown) => route.fulfill({ status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const blockers = (model: MooringModel) => {
    const list: { code: string; message: string }[] = [];
    const need = (okay: boolean, code: string, message: string) => { if (!okay) list.push({ code, message }); };
    need(model.vessels.some(v => v.heading === 'CONSTANT') && model.vessels.some(v => v.heading === 'MANOEUVRING'),
      'vessels.heading', 'Choose vessel heading roles.');
    need(!!model.rulerStepM, 'rulerStepM', 'Enter a ruler step.');
    for (const vessel of vessels) for (const code of ['PRIMARY_FENDER_SIZE', 'OIL_BOOM_LENGTH']) {
      const p = model.particulars.find(p => p.jobVesselId === vessel.jobVesselId && p.code === code);
      const key = `particular:${vessel.jobVesselId}:${code}`;
      need(!!p?.value && !!p.sourceText && verified.has(key), key, `Verify ${code} for ${vessel.role}.`);
    }
    need(model.lines.length > 0, 'lines', 'Add a line.');
    for (const line of model.lines) {
      need(!!line.fromPointId && !!line.toPointId, `lines.${line.label}.endpoints`, 'Choose both line endpoints.');
      for (const col of columns) {
        const c = line.capacities.find(c => c.columnCode === col.code);
        const key = `capacity:${line.id}:${col.code}`;
        need(!!c?.value && !!c.sourceText && verified.has(key), key, `Verify ${col.label} for ${line.label}.`);
      }
    }
    return list;
  };
  const view = (r: MooringRevision): MooringRevision => ({ ...r, snapshot: snapshot(),
    blockers: r.status === 'DRAFT' ? blockers(r.model) : [],
    allowedActions: r.status === 'DRAFT' ? ['EDIT', 'VERIFY_VALUE', 'SUBMIT'] :
      r.status === 'SUBMITTED' ? user === r.submittedByName ? ['RETURN'] : ['APPROVE', 'RETURN'] :
      r.status === 'APPROVED' ? ['ISSUE', 'RETURN'] : [],
  });
  const touch = (r: MooringRevision, changes: Partial<MooringRevision>) => Object.assign(r, changes, { version: r.version + 1 });

  await page.route('**/api/v1/auth/login', async route => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });
  await page.route('**/api/v1/jobs/job-1/technical/documents', route => json(route, 200, []));
  await page.route('**/api/v1/technical/mooring-rule', route => json(route, 200,
    { toleranceM: null, active: false, version: 0 }));
  await page.route('**/api/v1/jobs/job-1/mooring-plan**', async route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    if (path === '/api/v1/jobs/job-1/mooring-plan') return json(route, 200, { jobId: 'job-1', jobNumber: 'JOB/2026/00001',
      revisions: [...state.revisions].reverse().map(r => ({ id: r.id, revisionNo: r.revisionNo, status: r.status, issuedAt: r.issuedAt })),
      canCreate: !state.revisions.some(r => ['DRAFT', 'SUBMITTED', 'APPROVED'].includes(r.status)) });
    if (path.endsWith('/revisions') && method === 'POST') {
      const model: MooringModel = { schemaVersion: 1, vessels: vessels.map(v => ({ jobVesselId: v.jobVesselId,
        heading: null, lateralOffsetM: null, orientation: 'BOW_UP' })), layers: [], objects: [], lines: [], particulars: [], rulerStepM: null };
      const revision: MooringRevision = { id: `moor-${state.revisions.length + 1}`, revisionNo: state.revisions.length + 1,
        status: 'DRAFT', model, snapshot: snapshot(), snapshotFrozen: false, blockers: [], warnings: [],
        submittedByName: null, submittedAt: null, decidedByName: null, decidedAt: null, returnedReason: null,
        issuedByName: null, issuedAt: null, pdfSha256: null, allowedActions: [], version: 0 };
      state.revisions.push(revision); return json(route, 201, view(revision));
    }
    const match = path.match(/\/revisions\/(moor-\d+)(?:\/(.*))?$/);
    const r = state.revisions.find(rev => rev.id === match?.[1]);
    if (!r) return route.fallback();
    const action = match?.[2];
    if (!action && method === 'GET') return json(route, 200, view(r));
    if (!action && method === 'PUT') {
      const model = route.request().postDataJSON() as MooringModel;
      state.saves.push(model); verified.clear(); return json(route, 200, view(touch(r, { model })));
    }
    if (action === 'verify-value') {
      if (user === 'technical') return json(route, 422, { status: 422, code: 'SELF_VERIFICATION_FORBIDDEN' });
      verified.add((route.request().postDataJSON() as { key: string }).key);
      return json(route, 200, view(touch(r, {})));
    }
    if (action === 'submit') {
      if (blockers(r.model).length) return json(route, 400, { status: 400, code: 'MOORING_INCOMPLETE' });
      return json(route, 200, view(touch(r, { status: 'SUBMITTED', submittedByName: user, submittedAt: now, snapshotFrozen: true })));
    }
    if (action === 'decision') {
      const body = route.request().postDataJSON() as { decision: string; reason: string | null };
      if (body.decision === 'APPROVE' && user === r.submittedByName) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
      return json(route, 200, view(touch(r, body.decision === 'APPROVE'
        ? { status: 'APPROVED', decidedByName: user, decidedAt: now }
        : { status: 'DRAFT', returnedReason: body.reason, snapshotFrozen: false })));
    }
    if (action === 'issue') return json(route, 200, view(touch(r, { status: 'ISSUED', issuedByName: user,
      issuedAt: now, pdfSha256: 'a'.repeat(64) })));
    if (action === 'pdf') return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
    return route.fallback();
  });
  return state;
}
