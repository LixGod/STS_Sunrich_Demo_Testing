import { Page, Request, Route } from '@playwright/test';

/**
 * Commercial reference data and enquiry endpoints, layered over `mockApi` (Playwright gives later routes priority).
 * Synthetic data only; the server's own rules are covered by the Spring integration tests.
 */
export interface CommercialMock {
  created: { body: Record<string, unknown>; headers: Record<string, string> }[];
}

const page100 = (items: unknown[]) => ({ items, page: 0, size: 100, totalElements: items.length });

export async function mockCommercialApi(page: Page): Promise<CommercialMock> {
  const state: CommercialMock = { created: [] };
  const enquiries = new Map<string, Record<string, unknown>>();
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });

  const branches = [{ id: 'br-1', code: 'MUM', name: 'Demo Mumbai Branch', timezone: 'Asia/Kolkata', active: true }];
  const grades = [
    { id: 'g-1', code: 'PROPANE', name: 'Propane', category: 'LPG', defaultUnitId: 'u-1', active: true },
    { id: 'g-2', code: 'BUTANE', name: 'Butane', category: 'LPG', defaultUnitId: 'u-1', active: true },
  ];
  const units = [{ id: 'u-1', code: 'MT', name: 'Metric tonne', active: true }];
  const clients = [{ id: 'c-1', code: 'DEMO-CHART', legalName: 'DEMO Charterers Ltd (Synthetic)', countryCode: 'AE', phone: '+971 50 123 4567', active: true }];
  const vessels = [
    { id: 'v-1', imo: '1000007', name: 'DEMO MOTHER', active: true },
    { id: 'v-2', imo: '1000019', name: 'DEMO DAUGHTER', active: true },
  ];

  const detail = (id: string, body: Record<string, unknown>, status: string, history: unknown[], version: number) => {
    const vessel = (v: { vesselId: string } | null, role: string) => {
      if (!v) return null;
      const ref = vessels.find((x) => x.id === v.vesselId)!;
      return { role, vesselId: ref.id, imo: ref.imo, name: ref.name, etaUtc: null, etaTimezone: null, etaLocal: null };
    };
    const cargo = (body['cargo'] as { cargoGradeId: string; quantity: string; unitId: string }[]).map((line, i) => ({
      lineNo: i + 1, cargoGradeId: line.cargoGradeId, cargoGradeCode: grades.find((g) => g.id === line.cargoGradeId)?.code,
      cargoGradeName: null, quantity: Number(line.quantity), unitId: line.unitId, unitCode: 'MT',
    }));
    const open = status === 'DRAFT' || status === 'COSTING';
    return {
      id, reference: 'ENQ/2026/00001', branchId: 'br-1', branchCode: 'MUM',
      status, editable: open, allowedTransitions: status === 'DRAFT' ? ['COSTING', 'LOST', 'CANCELLED'] : status === 'COSTING' ? ['LOST', 'CANCELLED'] : [],
      receivedAt: body['receivedAt'], client: { id: 'c-1', code: 'DEMO-CHART', name: clients[0].legalName }, contact: null,
      operationType: body['operationType'] ?? null, cargoTypes: body['cargoTypes'] ?? [], expectedDurationHours: null, scopeOfServices: body['scopeOfServices'] ?? null,
      specialRequirements: null, sourceReference: null,
      motherVessel: vessel(body['motherVessel'] as { vesselId: string } | null, 'MOTHER'),
      daughterVessel: vessel(body['daughterVessel'] as { vesselId: string } | null, 'DAUGHTER'),
      cargo, history, version,
    };
  };

  const search = <T extends Record<string, unknown>>(request: Request, rows: T[], fields: (keyof T)[]) => {
    const q = (new URL(request.url()).searchParams.get('q') ?? '').toLowerCase();
    return page100(rows.filter((r) => !q || fields.some((f) => String(r[f]).toLowerCase().includes(q))));
  };

  await page.route('**/api/v1/masters/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1/masters', '');
    if (path === '/branches') return json(route, 200, page100(branches));
    if (path === '/cargo-grades') return json(route, 200, page100(grades));
    if (path === '/operation-types') return json(route, 200, page100([
      { code: 'PETROLEUM', name: 'Petroleum', active: true },
      { code: 'LPG', name: 'LPG', active: true },
      { code: 'LNG', name: 'LNG', active: true },
      { code: 'CHEMICAL', name: 'Chemical', active: true },
      { code: 'OTHER', name: 'Other', active: true },
    ]));
    if (path === '/units') return json(route, 200, page100(units));
    if (path === '/clients') return json(route, 200, search(request, clients, ['code', 'legalName']));
    if (path === '/clients/c-1') return json(route, 200, clients[0]);
    if (/^\/clients\/[^/]+\/contacts$/.test(path)) return json(route, 200, []);
    if (path === '/vessels' && request.method() === 'POST') {
      const body = request.postDataJSON() as { imo: string; name: string; vesselType: string };
      const created = { id: `v-${vessels.length + 1}`, imo: body.imo, name: body.name, active: true };
      vessels.push(created);
      return json(route, 201, { ...created, vesselType: body.vesselType, particularsStatus: 'UNVERIFIED', version: 0 });
    }
    if (path === '/vessels') return json(route, 200, search(request, vessels, ['name', 'imo']));
    return route.fallback();
  });

  await page.route('**/api/v1/enquiries**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace('/api/v1/enquiries', '');
    const method = request.method();

    if (path === '/duplicates') {
      return json(route, 200, [{ id: 'enq-0', reference: 'ENQ/2026/00000', status: 'COSTING', clientName: 'DEMO Charterers Ltd (Synthetic)',
        receivedAt: '2026-10-01T00:00:00Z', branchCode: 'MUM', motherVesselName: 'DEMO MOTHER', daughterVesselName: 'DEMO DAUGHTER',
        cargoGrades: [] }]);
    }
    if (path === '' && method === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>;
      state.created.push({ body, headers: request.headers() });
      const created = detail('enq-1', body, 'DRAFT', [{ fromStatus: null, toStatus: 'DRAFT', reason: null, occurredAt: new Date().toISOString() }], 0);
      enquiries.set('enq-1', created);
      return json(route, 201, created);
    }
    if (path === '' && method === 'GET') {
      return json(route, 200, page100([...enquiries.values()].map((e) => ({
        id: e['id'], reference: e['reference'], status: e['status'], receivedAt: e['receivedAt'], branchCode: 'MUM',
        clientName: clients[0].legalName, motherVesselName: 'DEMO MOTHER', daughterVesselName: 'DEMO DAUGHTER',
        cargoGrades: ['PROPANE'],
      }))));
    }
    const match = path.match(/^\/([^/]+)(\/transitions)?$/);
    const current = match ? enquiries.get(match[1]) : undefined;
    if (match && current && match[2] && method === 'POST') {
      const { to, reason } = request.postDataJSON() as { to: string; reason: string | null };
      if ((to === 'LOST' || to === 'CANCELLED') && !reason) {
        return json(route, 400, { status: 400, code: 'REASON_REQUIRED', errors: [{ field: 'reason', code: 'required' }] });
      }
      const history = [...(current['history'] as unknown[]), { fromStatus: current['status'], toStatus: to, reason, occurredAt: new Date().toISOString() }];
      const next = { ...current, status: to, history, version: (current['version'] as number) + 1,
        editable: to === 'COSTING', allowedTransitions: to === 'COSTING' ? ['LOST', 'CANCELLED'] : [] };
      enquiries.set(match[1], next);
      return json(route, 200, next);
    }
    if (match && current && method === 'GET') return json(route, 200, current);
    return json(route, 404, { status: 404, code: 'NOT_FOUND' });
  });

  return state;
}
