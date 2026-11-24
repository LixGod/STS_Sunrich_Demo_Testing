import { Page, Route } from '@playwright/test';

/**
 * Estimates, quotations and jobs for one costing enquiry (`enq-9`), layered over `mockApi` + `mockCommercialApi`.
 * Mirrors the server rules the screens depend on (status flow, no self-approval, one job per acceptance); the rules
 * themselves are covered by the Spring integration tests. Synthetic data only.
 */
export interface QuotationMock {
  estimateSaves: { lines: { kind: string; quantity: string; unitPrice: string }[] }[];
  acceptances: { key: string | undefined }[];
}

type Json = Record<string, unknown>;

const money = (n: number) => Math.round(n * 10000) / 10000;

export async function mockQuotationApi(page: Page): Promise<QuotationMock> {
  const state: QuotationMock = { estimateSaves: [], acceptances: [] };
  let user = '';
  let enquiryStatus = 'COSTING';
  const estimates: Json[] = [];
  const versions: Json[] = [];
  let job: Json | null = null;
  const seenKeys = new Set<string>();

  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });

  const heads = [
    { id: 'h-1', code: 'FENDERS', name: 'Fenders', kind: 'EXPENSE', active: true },
    { id: 'h-2', code: 'TUGS', name: 'Tugboats', kind: 'EXPENSE', active: true },
    { id: 'h-3', code: 'STS-SERVICE', name: 'STS service charge', kind: 'REVENUE', active: true },
    { id: 'h-4', code: 'ADD-TARIFF', name: 'Additional tariff', kind: 'REVENUE', active: true },
  ];
  const tariff = {
    id: 't-1', code: 'DEMO-STS', name: 'DEMO standard STS service', currency: 'USD', baseHours: 48,
    baseCharge: 10000, extraHourRate: 250, billingIncrementMinutes: null, graceMinutes: null, roundingRule: null,
    effectiveFrom: '2026-01-01', effectiveTo: null, termsPending: true, active: true,
  };
  const page100 = (items: unknown[]) => ({ items, page: 0, size: 100, totalElements: items.length });

  const enquiry = () => ({
    id: 'enq-9', reference: 'ENQ/2026/00009', branchId: 'br-1', branchCode: 'MUM',
    status: enquiryStatus, editable: enquiryStatus === 'COSTING', allowedTransitions: [], receivedAt: '2026-10-16T05:00:00Z',
    client: { id: 'c-1', code: 'DEMO-CHART', name: 'DEMO Charterers Ltd (Synthetic)' }, contact: null,
    operationType: 'LPG',
    expectedDurationHours: null, scopeOfServices: null, specialRequirements: null, sourceReference: null,
    motherVessel: { role: 'MOTHER', vesselId: 'v-1', imo: '1000007', name: 'DEMO MOTHER', etaUtc: null, etaTimezone: null, etaLocal: null },
    daughterVessel: { role: 'DAUGHTER', vesselId: 'v-2', imo: '1000019', name: 'DEMO DAUGHTER', etaUtc: null, etaTimezone: null, etaLocal: null },
    cargo: [{ lineNo: 1, cargoGradeId: 'g-1', cargoGradeCode: 'PROPANE', cargoGradeName: 'Propane', quantity: 34000, unitId: 'u-1', unitCode: 'MT' }],
    history: [], version: 3,
  });

  const withTotals = (estimate: Json) => {
    const lines = estimate['lines'] as Json[];
    const sum = (kind: string) => money(lines.filter((l) => l['kind'] === kind).reduce((s, l) => s + (l['amount'] as number), 0));
    const cost = sum('COST');
    const revenue = sum('REVENUE');
    const locked = versions.some((v) => v['estimateId'] === estimate['id']);
    return { ...estimate, totalCost: cost, totalRevenue: revenue, margin: money(revenue - cost),
      marginPercent: revenue > 0 ? Math.round(((revenue - cost) * 10000) / revenue) / 100 : null, locked, editable: true };
  };

  const actions = (v: Json) => {
    const isApprover = user === 'approver';
    switch (v['status']) {
      case 'DRAFT': return isApprover ? [] : ['EDIT', 'ISSUE'];
      case 'PENDING_APPROVAL':
      case 'APPROVED': return isApprover ? [] : ['EDIT', 'ISSUE'];
      case 'ISSUED': return isApprover ? [] : ['EDIT', 'ACCEPT'];
      default: return isApprover ? [] : ['EDIT'];
    }
  };

  const overview = () => ({
    enquiryId: 'enq-9', enquiryReference: 'ENQ/2026/00009', enquiryStatus, quotationId: versions.length ? 'q-1' : null,
    reference: versions.length ? 'QUO/2026/00001' : null,
    versions: [...versions].reverse().map((v) => ({ ...v, allowedActions: actions(v) })),
    canCreateVersion: user !== 'approver' && !versions.some((v) => ['DRAFT', 'PENDING_APPROVAL', 'APPROVED'].includes(v['status'] as string)),
    job: job ? { id: job['id'], jobNumber: job['jobNumber'] } : null,
  });

  const bump = (v: Json, changes: Json) => Object.assign(v, changes, { version: (v['version'] as number) + 1 });

  // The signed-in user, so actions match the server's (no self-approval).
  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/masters/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1/masters', '');
    if (path === '/cost-heads') return json(route, 200, page100(heads));
    if (path === '/currencies') return json(route, 200, page100([{ id: 'cur-1', code: 'USD', name: 'US dollar', minorUnits: 2, active: true }]));
    if (path === '/tariffs') return json(route, 200, page100([tariff]));
    return route.fallback();
  });

  await page.route(/\/api\/v1\/(enquiries\/enq-9|estimates|quotation-versions|jobs)(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1', '');
    const method = request.method();

    if (path === '/enquiries/enq-9' && method === 'GET') return json(route, 200, enquiry());

    if (path === '/enquiries/enq-9/estimates' && method === 'GET') {
      return json(route, 200, [...estimates].reverse().map((e) => {
        const t = withTotals(e);
        return { id: t.id, versionNo: t['versionNo'], currency: 'USD', inrFxRate: e['inrFxRate'] ?? null, totalCost: t.totalCost, totalRevenue: t.totalRevenue,
          marginPercent: t.marginPercent, locked: t.locked, updatedAt: null };
      }));
    }
    if (path === '/enquiries/enq-9/estimates' && method === 'POST') {
      const latest = estimates.at(-1);
      const created = { id: `est-${estimates.length + 1}`, enquiryId: 'enq-9', versionNo: estimates.length + 1, currency: 'USD',
        inrFxRate: latest?.['inrFxRate'] ?? null, notes: null, lines: latest ? structuredClone(latest['lines']) : [], updatedAt: null, version: 0 };
      estimates.push(created);
      return json(route, 201, withTotals(created));
    }
    // Inventory equipment for the cost-line picker (synthetic model, rate in the estimate currency).
    if (/^\/estimates\/[^/]+\/equipment-options$/.test(path)) {
      return json(route, 200, [{ modelId: 'm-1', name: 'Synthetic fender 3.3 m', categoryLabel: 'Primary fender', costHeadId: 'h-1',
        standardRate: 1250.125, standardRateCurrency: 'USD', available: 4 }]);
    }
    const estimateMatch = path.match(/^\/estimates\/([^/]+)$/);
    if (estimateMatch) {
      const estimate = estimates.find((e) => e['id'] === estimateMatch[1]);
      if (!estimate) return json(route, 404, { status: 404, code: 'NOT_FOUND' });
      if (method === 'PUT') {
        const body = request.postDataJSON() as { currency: string; inrFxRate?: number | null; lines: { kind: string; costHeadId: string; description: string | null; quantity: string; unitPrice: string; extraHours?: boolean }[] };
        state.estimateSaves.push(body);
        estimate['inrFxRate'] = body.inrFxRate ?? null;
        estimate['lines'] = body.lines.map((l, i) => {
          const head = heads.find((h) => h.id === l.costHeadId)!;
          return { lineNo: i + 1, kind: l.kind, costHeadId: l.costHeadId, costHeadCode: head.code, costHeadName: head.name,
            description: l.description, quantity: Number(l.quantity), unitPrice: Number(l.unitPrice),
            amount: money(Number(l.quantity) * Number(l.unitPrice)), extraHours: l.extraHours ?? false };
        });
        estimate['version'] = (estimate['version'] as number) + 1;
      }
      return json(route, 200, withTotals(estimate));
    }

    if (path === '/enquiries/enq-9/quotation' && method === 'GET') return json(route, 200, overview());
    if (path === '/enquiries/enq-9/quotation/versions' && method === 'POST') {
      const body = request.postDataJSON() as Json;
      const estimate = withTotals(estimates.find((e) => e['id'] === body['estimateId'])!);
      const revenue = (estimate['lines'] as Json[]).filter((l) => l['kind'] === 'REVENUE');
      versions.push({
        id: `qv-${versions.length + 1}`, versionNo: versions.length + 1, status: 'DRAFT', estimateId: estimate.id,
        estimateVersionNo: estimate['versionNo'], currency: 'USD', inrFxRate: body['inrFxRate'] ?? estimate['inrFxRate'] ?? null, totalAmount: estimate.totalRevenue,
        finalAmount: money(estimate.totalRevenue * 1.18), advancePaid: 0, advanceRemaining: money(estimate.totalRevenue * 1.18),
        advanceStatus: 'UNPAID', advancePayments: [], validUntil: body['validUntil'],
        paymentTerms: body['paymentTerms'], termsText: body['termsText'], tariff: body['tariffId'] ? tariff : null,
        lines: revenue.map((l, i) => ({ lineNo: i + 1, costHeadCode: l['costHeadCode'], costHeadName: l['costHeadName'],
          description: l['description'], quantity: l['quantity'], unitPrice: l['unitPrice'], amount: l['amount'] })),
        submittedByName: null, submittedAt: null, decidedByName: null, decidedAt: null, decisionReason: null, issuedByName: null,
        issuedAt: null, issuedTo: null, issueChannel: null, issueNote: null, pdfSha256: null, acceptance: null, updatedAt: null, version: 0,
      });
      return json(route, 201, overview());
    }
    if (path === '/quotation-versions/pending-approval') {
      return json(route, 200, versions.filter((v) => v['status'] === 'PENDING_APPROVAL' && v['submittedBy'] !== user).map((v) => ({
        versionId: v['id'], enquiryId: 'enq-9', enquiryReference: 'ENQ/2026/00009', quotationReference: 'QUO/2026/00001',
        versionNo: v['versionNo'], clientName: 'DEMO Charterers Ltd (Synthetic)', currency: 'USD', totalAmount: v['totalAmount'],
        submittedByName: 'Demo Commercial Executive', submittedAt: v['submittedAt'],
      })));
    }
    const versionMatch = path.match(/^\/quotation-versions\/([^/]+)\/(submit|decision|issue|acceptance)$/);
    if (versionMatch && method === 'POST') {
      const v = versions.find((x) => x['id'] === versionMatch[1])!;
      const now = new Date().toISOString();
      switch (versionMatch[2]) {
        case 'submit':
          if (!v['tariff']) return json(route, 400, { status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'tariffId', code: 'required' }] });
          bump(v, { status: 'PENDING_APPROVAL', submittedBy: user, submittedByName: 'Demo Commercial Executive', submittedAt: now });
          enquiryStatus = 'QUOTATION_APPROVAL';
          break;
        case 'decision': {
          if (v['submittedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
          const { approve, reason } = request.postDataJSON() as { approve: boolean; reason: string | null };
          bump(v, { status: approve ? 'APPROVED' : 'REJECTED', decidedByName: 'Demo Management Approver', decidedAt: now, decisionReason: reason });
          if (!approve) enquiryStatus = 'COSTING';
          break;
        }
        case 'issue': {
          if (!v['tariff']) return json(route, 400, { status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'tariffId', code: 'required' }] });
          const { issuedTo, channel } = request.postDataJSON() as { issuedTo: string; channel: string };
          bump(v, { status: 'ISSUED', issuedByName: 'Demo Commercial Executive', issuedAt: now, issuedTo, issueChannel: channel, pdfSha256: 'a'.repeat(64) });
          enquiryStatus = 'QUOTED';
          break;
        }
        case 'acceptance': {
          const key = request.headers()['idempotency-key'];
          state.acceptances.push({ key });
          if (key && seenKeys.has(key)) return json(route, 200, overview());
          if (v['status'] === 'ACCEPTED') return json(route, 409, { status: 409, code: 'ALREADY_ACCEPTED' });
          if (key) seenKeys.add(key);
          const body = request.postDataJSON() as Json;
          job = {
            id: 'job-1', jobNumber: (body['jobNumber'] as string | null) || 'JOB/2026/00001', status: 'OPEN', branchCode: 'MUM',
            enquiryId: 'enq-9', enquiryReference: 'ENQ/2026/00009', quotationReference: 'QUO/2026/00001 v1',
            client: { id: 'c-1', code: 'DEMO-CHART', name: 'DEMO Charterers Ltd (Synthetic)' }, contact: null,
            operationType: 'LPG', currency: 'USD',
            quotedAmount: v['totalAmount'], vessels: [enquiry().motherVessel, enquiry().daughterVessel], cargo: enquiry().cargo,
            tariff: { tariffCode: tariff.code, tariffName: tariff.name, currency: 'USD', baseHours: 48, baseCharge: 10000, extraHourRate: 250,
              billingIncrementMinutes: null, graceMinutes: null, roundingRule: null, effectiveFrom: '2026-01-01', effectiveTo: null, capturedAt: now },
            createdAt: now, version: 0,
          };
          bump(v, { status: 'ACCEPTED', acceptance: { acceptedAt: body['acceptedAt'], acceptedByName: body['acceptedByName'],
            workOrderReference: body['workOrderReference'], paymentTerms: body['paymentTerms'], specialConditions: null, afterValidity: false,
            recordedByName: 'Demo Commercial Executive', recordedAt: now, jobId: 'job-1', jobNumber: job['jobNumber'] } });
          enquiryStatus = 'ACCEPTED';
          break;
        }
      }
      return json(route, 200, overview());
    }
    if (path === '/jobs' && method === 'GET') return json(route, 200, page100(job ? [job] : []));
    if (path === '/jobs/job-1' && job) return json(route, 200, job);
    return route.fallback();
  });

  return state;
}
