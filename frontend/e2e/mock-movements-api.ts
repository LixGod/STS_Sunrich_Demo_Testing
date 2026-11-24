import { Page, Route } from '@playwright/test';

interface Trip { id: string; jobId: string; craftId: string | null; craftIdentifier: string | null; vendorId: string | null;
  vendorName: string | null; departedAt: string; returnedAt: string | null; timeZone: string;
  assets: { allocationId: string; assetId: string; identifier: string; category: string }[];
  serviceNotes: string | null; standbyNotes: string | null; delayNotes: string | null; damageNotes: string | null;
  status: string; files: unknown[]; pendingAction: string | null; pendingJson: string | null; pendingReason: string | null;
  pendingBy: string | null; version: number }

export async function mockMovementsApi(page: Page) {
  let user = '';
  const trips: Trip[] = [];
  let selection: { startMovementId: string; stopMovementId: string | null; basisHours: number;
    overrideReason: string | null; pendingStartId: string | null; pendingStopId: string | null;
    pendingReason: string | null; requestedBy: string | null; version: number } | null = null;
  let notice: { id: string; status: string; snapshotJson: string; deliveryOutcome: string | null; version: number } | null = null;
  let recovery = { jobId: 'job-1', disembarkedAt: null as string | null, status: 'OPEN', version: 0 };
  let returnStatus = 'UNRECORDED', notes: string | null = null, resolutionReason: string | null = null, assetVersion = 0;
  const send = (route: Route, status: number, value: unknown) => route.fulfill({ status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(value) });
  const fail = (route: Route, code: string, status = 422) => send(route, status, { status, code });
  const assets = () => trips.length ? [{ allocationId: 'allocation-1', identifier: 'SYN-FENDER-1', category: 'PRIMARY_FENDER',
    status: returnStatus, notes, fileId: null, resolutionReason, resolvedBy: resolutionReason ? 'admin' : null, version: assetVersion }] : [];
  const recoveryView = () => ({ ...recovery, assets: assets(), unresolved: assets().filter(a => a.status === 'UNRECORDED' ||
    (a.status !== 'RETURNED' && !a.resolutionReason)).map(a => a.allocationId) });
  const clockView = () => {
    const start = trips.find(t => t.id === selection?.startMovementId);
    const stop = trips.find(t => t.id === selection?.stopMovementId);
    const elapsed = start ? (Date.parse(stop?.returnedAt ?? new Date().toISOString()) - Date.parse(start.departedAt)) / 3600000 : null;
    const extra = elapsed === null ? null : Math.max(0, elapsed - (selection?.basisHours ?? 48));
    if (extra !== null && extra > 0 && !notice) notice = { id: 'notice-1', status: 'DRAFT', snapshotJson: '{}',
      deliveryOutcome: null, version: 0 };
    const expiryAt = start ? new Date(Date.parse(start.departedAt) + (selection?.basisHours ?? 48) * 3600000).toISOString() : null;
    return { clock: { startMovementId: selection?.startMovementId ?? null, stopMovementId: selection?.stopMovementId ?? null,
      startAt: start?.departedAt ?? null, stopAt: stop?.returnedAt ?? null, basisHours: selection?.basisHours ?? 48,
      elapsedHours: elapsed, extraHours: extra, tariffTermsStatus: 'PENDING_TARIFF_TERMS',
      billableExtraUnits: null, additionalCharge: null, currency: 'USD', final: !!stop?.returnedAt },
      selection, expiryAt, countdownSeconds: expiryAt ? Math.round((Date.parse(expiryAt) -
        Date.parse(stop?.returnedAt ?? new Date().toISOString())) / 1000) : null,
      exceeded: extra !== null && extra > 0, forecastCompletion: null,
      approvedAdjustmentHours: 0, notice, adjustments: [] };
  };

  await page.route('**/api/v1/**', async route => {
    const req = route.request(); const path = new URL(req.url()).pathname.replace('/api/v1', '');
    const method = req.method();
    if (path === '/auth/login') { user = req.postDataJSON()?.username ?? ''; return route.fallback(); }
    if (path === '/auth/logout') { user = ''; return route.fallback(); }
    if (!path.startsWith('/jobs/job-1/movements') && !path.startsWith('/jobs/job-1/recovery')) return route.fallback();
    if (path === '/jobs/job-1/movements/context') return send(route, 200, { jobNumber: 'SYN-JOB-1', timeZone: 'Asia/Kolkata' });
    if (path === '/jobs/job-1/movements' && method === 'GET') return send(route, 200,
      { items: trips, page: 0, size: 25, totalElements: trips.length });
    if (path === '/jobs/job-1/movements/dispatch' && method === 'POST') {
      if (trips.some(t => t.status === 'ACTIVE')) return fail(route, 'INVALID_TRANSITION');
      const body = req.postDataJSON();
      const row: Trip = { id: `trip-${trips.length + 1}`, jobId: 'job-1',
        craftId: null, craftIdentifier: null, vendorId: null, vendorName: null,
        departedAt: body.departedAt, returnedAt: null, timeZone: 'Asia/Kolkata',
        assets: ['allocation-1'].map((id: string) => ({ allocationId: id, assetId: 'asset-1',
          identifier: 'SYN-FENDER-1', category: 'PRIMARY_FENDER' })), serviceNotes: null,
        standbyNotes: null, delayNotes: null, damageNotes: null,
        status: 'ACTIVE', files: [], pendingAction: null, pendingJson: null, pendingReason: null, pendingBy: null, version: 0 };
      trips.unshift(row);
      selection = { startMovementId: row.id, stopMovementId: null, basisHours: selection?.basisHours ?? 48,
        overrideReason: selection?.overrideReason ?? null, pendingStartId: null, pendingStopId: null,
        pendingReason: null, requestedBy: null, version: 0 };
      return send(route, 201, row);
    }
    const returned = path.match(/^\/jobs\/job-1\/movements\/(trip-\d+)\/return$/);
    if (returned && method === 'POST') { const row = trips.find(t => t.id === returned[1]);
      if (!row) return fail(route, 'NOT_FOUND', 404);
      row.returnedAt = req.postDataJSON().returnedAt; row.version++;
      if (selection?.startMovementId === row.id) selection.stopMovementId = row.id;
      return send(route, 200, row); }
    const decision = path.match(/^\/jobs\/job-1\/movements\/(trip-\d+)\/decision$/);
    if (decision && method === 'POST') {
      const row = trips.find(t => t.id === decision[1]);
      if (!row) return fail(route, 'NOT_FOUND', 404);
      if (row.pendingBy === user) return fail(route, 'SELF_APPROVAL_FORBIDDEN');
      row.pendingAction = null; row.pendingJson = null; row.pendingReason = null; row.pendingBy = null; row.version++;
      return send(route, 200, row);
    }
    if (path === '/jobs/job-1/movements/clock' && method === 'GET') return send(route, 200, clockView());
    if (path === '/jobs/job-1/movements/clock/selection' && method === 'POST') {
      const body = req.postDataJSON();
      if (!selection) selection = { startMovementId: body.startMovementId, stopMovementId: body.stopMovementId,
        basisHours: 48, overrideReason: null, pendingStartId: null, pendingStopId: null,
        pendingReason: null, requestedBy: null, version: 0 };
      else { selection.pendingStartId = body.startMovementId; selection.pendingStopId = body.stopMovementId;
        selection.pendingReason = body.reason; selection.requestedBy = user; selection.version++; }
      return send(route, 200, selection);
    }
    if (path === '/jobs/job-1/movements/clock/notice/release' && method === 'POST') {
      if (!notice) return fail(route, 'NOT_FOUND', 404);
      notice.status = 'RELEASED'; notice.version++; return send(route, 200, notice); }
    if (path === '/jobs/job-1/recovery' && method === 'GET') return send(route, 200, recoveryView());
    if (path === '/jobs/job-1/recovery/disembark' && method === 'POST') {
      recovery.disembarkedAt = req.postDataJSON().at; recovery.version++; return send(route, 200, recoveryView()); }
    const result = path.match(/^\/jobs\/job-1\/recovery\/assets\/([^/]+)$/);
    if (result && method === 'PUT') { const body = req.postDataJSON(); returnStatus = body.status;
      notes = body.notes; resolutionReason = null; assetVersion++; return send(route, 200, recoveryView()); }
    const resolved = path.match(/^\/jobs\/job-1\/recovery\/assets\/([^/]+)\/resolve$/);
    if (resolved && method === 'POST') { resolutionReason = req.postDataJSON().reason; assetVersion++;
      return send(route, 200, recoveryView()); }
    if (path === '/jobs/job-1/recovery/close' && method === 'POST') {
      if (recoveryView().unresolved.length) return fail(route, 'RECOVERY_DISCREPANCY');
      recovery.status = 'CLOSED'; recovery.version++; return send(route, 200, recoveryView()); }
    return fail(route, 'NOT_FOUND', 404);
  });
  return { trips, recoveryView, clockView };
}
