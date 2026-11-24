import { Page, Route } from '@playwright/test';
import { MockUser } from './mock-api';

/** In-browser stand-in for the final report endpoints (Phase 6a), synthetic data only. */
export const PREPARER: MockUser = {
  username: 'ops.mumbai', password: 'ops', fullName: 'Demo Operations (Mumbai)',
  permissions: ['JOB.READ', 'REPORT.FINAL.READ', 'REPORT.FINAL.WRITE'],
  grants: [{ roleCode: 'OPERATIONS', branchId: 'br-1' }],
};

export const MANAGER: MockUser = {
  username: 'approver', password: 'approver', fullName: 'Demo Management Approver',
  permissions: ['JOB.READ', 'REPORT.FINAL.READ', 'REPORT.FINAL.APPROVE'],
  grants: [{ roleCode: 'MANAGEMENT_APPROVER', branchId: null }],
};

const now = '2026-10-18T05:00:00Z';
type Json = Record<string, unknown>;

export interface FinalReportMock { saves: Json[]; decisions: Json[] }

export async function mockFinalReportApi(page: Page): Promise<FinalReportMock> {
  const state: FinalReportMock = { saves: [], decisions: [] };
  let user = 'ops.mumbai';
  const names: Record<string, string> = { 'ops.mumbai': PREPARER.fullName, approver: MANAGER.fullName };
  const revisions: Json[] = [];
  const snapshot = {
    jobNumber: 'JOB/2026/00001', client: 'DEMO Charterer', timezone: 'Asia/Kolkata',
    operationType: 'LPG',
    vessels: [{ role: 'MOTHER', name: 'DEMO MOTHER', imo: '1000007', type: 'LPG_CARRIER' }, { role: 'DAUGHTER', name: 'DEMO DAUGHTER', imo: '1000015', type: 'LPG_CARRIER' }],
    cargo: [{ gradeName: 'Propane', gradeCode: 'PROPANE', quantity: 3000, unit: 'MT' }],
    q88: [{ vessel: 'DEMO MOTHER', imo: '1000007', documentName: 'demo-q88.pdf', uploadedAt: now }],
    events: [{ phase: 'BERTHING', label: 'All fast', vessel: null, occurredAt: now, startedAt: null, endedAt: null, remarks: null }],
    grades: [{ gradeName: 'Propane', gradeCode: 'PROPANE', unit: 'MT', planned: 3000, finalQuantity: 3000, completed: true }],
    delays: [], incidents: [], sof: { revisionNo: 1, issuedAt: now, pdfSha256: 'c'.repeat(64) }, attachments: [], takenAt: now,
  };
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const blockers = (c: Json) => [
    ...(!c['summary'] ? [{ code: 'summary', message: 'Enter the executive summary.' }] : []),
    ...(!(c['attendance'] as Json[]).length ? [{ code: 'attendance', message: 'Enter the supervision and attendance (at least one person).' }] : []),
  ];
  const view = (r: Json) => {
    const actions: string[] = [];
    const can = (p: string) => [PREPARER, MANAGER].find((u) => u.username === user)!.permissions.includes(p);
    if (r['status'] === 'DRAFT' && can('REPORT.FINAL.WRITE')) actions.push('EDIT', 'SUBMIT');
    if (r['status'] === 'SUBMITTED' && can('REPORT.FINAL.APPROVE')) { if (r['submittedBy'] !== user) actions.push('APPROVE'); actions.push('RETURN'); }
    if (r['status'] === 'APPROVED' && can('REPORT.FINAL.WRITE')) actions.push('ISSUE');
    return { ...r, snapshot, snapshotFrozen: r['status'] !== 'DRAFT', blockers: r['status'] === 'DRAFT' ? blockers(r['content'] as Json) : [],
      warnings: [], sources: { equipment: 'MANUAL', attendance: 'MANUAL' }, allowedActions: actions,
      submittedByName: r['submittedBy'] ? names[r['submittedBy'] as string] : null };
  };
  const touch = (r: Json, changes: Json) => Object.assign(r, changes, { version: (r['version'] as number) + 1 });

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/jobs/job-1/technical/documents') return json(route, 200, [{ id: 'doc-1', fileName: 'demo-sof-signed.pdf' }]);
    if (path === '/jobs/job-1/final-report') {
      return json(route, 200, { jobId: 'job-1', jobNumber: snapshot.jobNumber,
        revisions: [...revisions].reverse().map((r) => ({ id: r['id'], revisionNo: r['revisionNo'], status: r['status'], issuedAt: r['issuedAt'] })),
        canCreate: user === 'ops.mumbai' && !revisions.some((r) => ['DRAFT', 'SUBMITTED', 'APPROVED'].includes(r['status'] as string)) });
    }
    if (path === '/jobs/job-1/final-report/revisions' && method === 'POST') {
      const r: Json = { id: `fr-${revisions.length + 1}`, revisionNo: revisions.length + 1, reference: `${snapshot.jobNumber} · FR${revisions.length + 1}`,
        documentId: `fr-${revisions.length + 1}`, templateVersion: 1, status: 'DRAFT',
        content: { vhfChannel: null, summary: 'STS operation for DEMO Charterer at Demo Anchorage A1.\nNo incidents or delays were recorded in the operations log.',
          remarks: null, equipment: [], attendance: [], attachmentDocumentIds: [] },
        submittedBy: null, submittedAt: null, decidedByName: null, decidedAt: null, returnedReason: null, issuedByName: null, issuedAt: null,
        pdfSha256: null, updatedAt: now, version: 0 };
      revisions.push(r);
      return json(route, 201, view(r));
    }
    const m = path.match(/^\/jobs\/job-1\/final-report\/revisions\/([\w-]+)(?:\/([\w-]+))?$/);
    const r = m && revisions.find((x) => x['id'] === m[1]);
    if (!m || !r) return route.fallback();
    const action = m[2];
    if (!action && method === 'GET') return json(route, 200, view(r));
    if (!action && method === 'PUT') {
      const body = route.request().postDataJSON() as Json;
      state.saves.push(body);
      return json(route, 200, view(touch(r, { content: body })));
    }
    if (action === 'submit') {
      if (blockers(r['content'] as Json).length) return json(route, 400, { status: 400, code: 'REPORT_INCOMPLETE' });
      return json(route, 200, view(touch(r, { status: 'SUBMITTED', submittedBy: user, submittedAt: now })));
    }
    if (action === 'decision') {
      const body = route.request().postDataJSON() as Json;
      state.decisions.push(body);
      return json(route, 200, view(touch(r, body['decision'] === 'APPROVE' ? { status: 'APPROVED', decidedByName: names[user], decidedAt: now }
        : { status: 'DRAFT', returnedReason: body['reason'] })));
    }
    if (action === 'issue') return json(route, 200, view(touch(r, { status: 'ISSUED', issuedByName: names[user], issuedAt: now, pdfSha256: 'd'.repeat(64) })));
    return route.fallback();
  });

  return state;
}
