import { Page, Route } from '@playwright/test';

interface Allocation { id: string; jobId: string; kind: string; resourceId: string; identifier: string; category: string;
  startAt: string; endAt: string; timeZone: string; status: string; collisionIds: string[];
  eligibilityMessages: string[]; exceptionReason: string | null; requestedBy: string | null;
  approvedBy: string | null; decisionReason: string | null; version: number }

/** Synthetic resource data for Angular browser tests; backend rules are tested against MySQL. */
export async function mockResourcesApi(page: Page) {
  let user = '';
  let serial = 0;
  const codes: Record<string, { code: string; label: string; active: boolean; version: number }[]> = {
    categories: [{ code: 'CARGO_HOSE', label: 'Cargo hose', active: true, version: 0 }],
    competencies: [], 'certificate-types': [{ code: 'HOSE_TEST', label: 'Hose test', active: true, version: 0 }],
  };
  const models: Record<string, unknown>[] = [];
  const assets: Record<string, unknown>[] = [];
  const people: Record<string, unknown>[] = [];
  const vendors: Record<string, unknown>[] = [];
  const craft: Record<string, unknown>[] = [];
  const certificates: Record<string, unknown>[] = [];
  const inspections: Record<string, unknown>[] = [];
  const allocations: Allocation[] = [];
  const policy = { mode: 'WARN', warningDays: 10, requirementsConfigured: true, version: 1 };
  const respond = (route: Route, status: number, data: unknown) => route.fulfill({ status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(data) });
  const error = (route: Route, status: number, code: string) => respond(route, status, { status, code });
  const pageOf = <T>(items: T[], url: URL) => {
    const page = Number(url.searchParams.get('page') ?? 0), size = Math.min(100, Number(url.searchParams.get('size') ?? 25));
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const [field, direction] = (url.searchParams.get('sort') ?? '').split(',');
    const filtered = items.filter(row => !q || Object.values(row as object).some(value => String(value ?? '').toLowerCase().includes(q)));
    if (field) filtered.sort((a, b) => String((a as Record<string, unknown>)[field] ?? '').localeCompare(
      String((b as Record<string, unknown>)[field] ?? '')) * (direction === 'desc' ? -1 : 1));
    return { items: filtered.slice(page * size, (page + 1) * size), page, size, totalElements: filtered.length };
  };

  await page.route('**/api/v1/**', async route => {
    const req = route.request(); const url = new URL(req.url()); const path = url.pathname.replace('/api/v1', '');
    const method = req.method();
    if (path === '/auth/login') { user = req.postDataJSON()?.username ?? ''; return route.fallback(); }
    if (path === '/auth/logout') { user = ''; return route.fallback(); }
    if (path.startsWith('/resources/')) {
      const rest = path.slice('/resources/'.length);
      if (rest in codes) {
        if (method === 'GET') return respond(route, 200, codes[rest]);
        if (method === 'POST') { const row = { ...req.postDataJSON(), version: 0 }; codes[rest]!.push(row);
          return respond(route, 201, row); }
      }
      if (rest === 'models') {
        if (method === 'GET') return respond(route, 200, pageOf(models, url));
        const row = { ...req.postDataJSON(), id: `model-${++serial}`, version: 0 }; models.push(row);
        return respond(route, 201, row);
      }
      if (rest === 'assets') {
        if (method === 'GET') return respond(route, 200, pageOf(assets, url));
        const input = req.postDataJSON(); const model = models.find(m => m['id'] === input.modelId);
        const row = { ...input, id: `asset-${++serial}`, categoryCode: model?.['categoryCode'] ?? 'CARGO_HOSE', version: 0 };
        assets.push(row); return respond(route, 201, row);
      }
      if (rest === 'inventory') return respond(route, 200, [{ modelId: 'model-inv', modelCode: 'SYN-FENDER', modelName: 'Synthetic fender',
        categoryCode: 'PRIMARY_FENDER', categoryLabel: 'Primary fender', original: 6, inUse: 2, available: 1, maintenance: 0,
        damaged: 1, lost: 1, retired: 1, revised: 3 }]);
      if (rest === 'inventory/model-inv') return respond(route, 200, [
        { assetId: 'a-1', assetIdentifier: 'SYN-F-01', status: 'AVAILABLE', inUse: true, jobId: 'job-1', jobNumber: 'DEMO-JOB-1', history: [] },
        { assetId: 'a-2', assetIdentifier: 'SYN-F-02', status: 'LOST', inUse: false, jobId: null, jobNumber: null,
          history: [{ jobId: 'job-0', jobNumber: 'DEMO-JOB-0', result: 'SHORT', notes: null, resolution: 'Not found after recovery', at: '2026-10-01T06:00:00Z' }] },
      ]);
      const lists: Record<string, Record<string, unknown>[]> = { models, assets, personnel: people, vendors, craft };
      const list = lists[rest];
      if (list) {
        if (method === 'GET') return respond(route, 200, pageOf(list, url));
        if (method === 'POST') { const row = { ...req.postDataJSON(), id: `${rest}-${++serial}`, version: 0 };
          list.push(row); return respond(route, 201, row); }
      }
      const edit = rest.match(/^(models|assets|personnel|vendors|craft)\/([^/]+)$/);
      if (edit && method === 'GET') {
        const row = lists[edit[1]!]!.find(item => item['id'] === edit[2]);
        return row ? respond(route, 200, row) : error(route, 404, 'NOT_FOUND');
      }
      if (edit && method === 'PUT') {
        const row = lists[edit[1]!]!.find(item => item['id'] === edit[2]);
        if (!row) return error(route, 404, 'NOT_FOUND');
        Object.assign(row, req.postDataJSON(), { version: Number(row['version']) + 1 });
        return respond(route, 200, row);
      }
      if (rest === 'requirements') return respond(route, 200, []);
      if (rest === 'policy') return respond(route, 200, policy);
      const certificatePath=rest.match(/^(assets|personnel|craft)\/([^/]+)\/certificates(?:\/([^/]+)(?:\/history)?)?$/);
      if (certificatePath) {
        const owner=certificatePath[2]!, id=certificatePath[3];
        if (rest.endsWith('/history')) return respond(route,200,[{ occurredAt:'2028-01-01T00:00:00Z',
          actorName:'Synthetic administrator',details:'Original certificate' }]);
        if (method==='GET') return respond(route,200,certificates.filter(row => row['ownerId']===owner));
        if (method==='PUT' && id) { const row=certificates.find(item => item['id']===id);
          if(!row) return error(route,404,'NOT_FOUND');
          const body=req.postData() ?? '';
          const embedded=body.match(/\{[^\r\n]*"typeCode"[^\r\n]*\}/);
          Object.assign(row,embedded ? JSON.parse(embedded[0]) : req.postDataJSON(),{ version:Number(row['version'])+1 });
          return respond(route,200,row); }
      }
      const inspectionPath=rest.match(/^assets\/([^/]+)\/inspections(?:\/([^/]+)(?:\/history)?)?$/);
      if (inspectionPath) {
        const owner=inspectionPath[1]!, id=inspectionPath[2];
        if (rest.endsWith('/history')) return respond(route,200,[{ occurredAt:'2028-01-01T00:00:00Z',
          actorName:'Synthetic administrator',details:'Original inspection' }]);
        if (method==='GET') return respond(route,200,inspections.filter(row => row['assetId']===owner));
        if (method==='PUT' && id) { const row=inspections.find(item => item['id']===id);
          if(!row) return error(route,404,'NOT_FOUND'); Object.assign(row,req.postDataJSON(),{ version:Number(row['version'])+1 });
          return respond(route,200,row); }
      }
      return error(route, 404, 'NOT_FOUND');
    }
    const match = path.match(/^\/jobs\/([^/]+)\/resources\/allocations(?:\/(.*))?$/);
    if (!match) return route.fallback();
    const jobId = match[1]!; const action = match[2];
    if (action === 'context') return respond(route, 200, { jobId, jobNumber: 'DEMO-001', timeZone: 'Asia/Kolkata' });
    if (action === 'choices') return respond(route, 200, { assets: assets.map(a => ({ id: a['id'], identifier: a['assetIdentifier'],
      label: a['assetIdentifier'], category: a['categoryCode'] })), people: [], craft: [], policy });
    if (!action && method === 'GET') return respond(route, 200, pageOf(allocations.filter(a => a.jobId === jobId), url));
    if (!action && method === 'POST') {
      const input = req.postDataJSON(); const conflicts = allocations.filter(a => a.status === 'ACTIVE' && a.resourceId === input.resourceId &&
        a.startAt < input.endAt && input.startAt < a.endAt);
      if (conflicts.length && !input.exceptionReason) return error(route, 409, 'ALLOCATION_COLLISION');
      const asset = assets.find(a => a['id'] === input.resourceId);
      const row: Allocation = { ...input, id: `allocation-${++serial}`, jobId, identifier: String(asset?.['assetIdentifier'] ?? 'Hose'),
        category: String(asset?.['categoryCode'] ?? 'CARGO_HOSE'), timeZone: 'Asia/Kolkata',
        status: conflicts.length ? 'PENDING_EXCEPTION' : 'ACTIVE', collisionIds: conflicts.map(c => c.id),
        eligibilityMessages: ['Hose test certificate expires within 10 days.'], requestedBy: conflicts.length ? user : null,
        approvedBy: null, decisionReason: null, version: 0 };
      allocations.unshift(row); return respond(route, 201, row);
    }
    const decision = action?.match(/^([^/]+)\/decision$/);
    if (decision && method === 'POST') {
      const row = allocations.find(a => a.id === decision[1] && a.jobId === jobId);
      if (!row) return error(route, 404, 'NOT_FOUND');
      if (row.requestedBy === user) return error(route, 422, 'SELF_APPROVAL_FORBIDDEN');
      row.status = req.postDataJSON().decision === 'APPROVE' ? 'ACTIVE' : 'REJECTED';
      row.approvedBy = user; row.version++; return respond(route, 200, row);
    }
    return error(route, 404, 'NOT_FOUND');
  });
  return { models, assets, people, vendors, craft, certificates, inspections, allocations };
}
