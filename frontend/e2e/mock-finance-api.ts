import { Page, Route } from '@playwright/test';
import { MockUser } from './mock-api';

/** In-browser stand-in for the job billing roles, synthetic data only. */
export const OPERATOR: MockUser = {
  username: 'operations', password: 'operations', fullName: 'Demo Operations Manager',
  permissions: ['JOB.READ', 'OPS.LIVE.READ', 'OPS.LIVE.WRITE', 'OPS.MOVEMENT.READ', 'FIN.INVOICE.READ', 'FIN.INVOICE.WRITE',
    'FIN.COST.READ', 'FIN.COST.WRITE', 'FIN.FDA.READ', 'FIN.FDA.WRITE'],
  grants: [{ roleCode: 'OPERATIONS', branchId: null }],
};

export const FINANCE: MockUser = {
  username: 'finance', password: 'finance', fullName: 'Demo Finance',
  permissions: ['JOB.READ', 'FIN.INVOICE.READ', 'FIN.INVOICE.APPROVE', 'FIN.RECEIPT.READ', 'FIN.RECEIPT.WRITE',
    'FIN.COST.READ', 'FIN.COST.APPROVE', 'FIN.FDA.READ', 'FIN.PROFIT.READ', 'OPS.MOVEMENT.READ'],
  grants: [{ roleCode: 'FINANCE', branchId: null }],
};

export const FIN_APPROVER: MockUser = {
  username: 'approver', password: 'approver', fullName: 'Demo Management Approver',
  permissions: ['JOB.READ', 'FIN.INVOICE.READ', 'FIN.RECEIPT.READ', 'FIN.COST.READ', 'FIN.COST.PO.APPROVE', 'FIN.FDA.READ',
    'FIN.FDA.APPROVE', 'FIN.PROFIT.READ'],
  grants: [{ roleCode: 'MANAGEMENT_APPROVER', branchId: null }],
};

const now = '2026-10-18T05:00:00Z';
const JOB = 'JOB/2026/00001';
const CLIENT = 'DEMO Charterers Ltd (Synthetic)';
type Json = Record<string, unknown>;

export interface FinanceMock { saves: Json[]; decisions: Json[]; receipts: Json[] }

const round = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;

export async function mockFinanceApi(page: Page): Promise<FinanceMock> {
  const state: FinanceMock = { saves: [], decisions: [], receipts: [] };
  let user = 'finance';
  const users = [OPERATOR, FINANCE, FIN_APPROVER];
  const names: Record<string, string> = { operations: OPERATOR.fullName, finance: FINANCE.fullName, approver: FIN_APPROVER.fullName };
  const invoices: Json[] = [];
  const receipts: Json[] = [];
  let sequence = 0;
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const can = (p: string) => users.find((u) => u.username === user)!.permissions.includes(p);

  const priced = (lines: Json[]) => lines.map((l, i) => {
    const amount = round(Number(l['quantity']) * Number(l['unitPrice']));
    return { lineNo: i + 1, source: l['source'], costHeadCode: l['costHeadCode'], costHeadName: 'STS service charge', description: l['description'] ?? null,
      quantity: Number(l['quantity']), unitPrice: Number(l['unitPrice']), amount, taxCode: null,
      taxRate: null, taxAmount: 0 };
  });
  const totals = (inv: Json) => {
    const lines = inv['lines'] as { amount: number; taxAmount: number }[];
    const net = round(lines.reduce((s, l) => s + l.amount, 0));
    const tax = round(lines.reduce((s, l) => s + l.taxAmount, 0));
    Object.assign(inv, { netAmount: net, taxAmount: tax, grossAmount: round(net + tax) });
  };
  const received = (inv: Json) => round(receipts.filter((r) => r['status'] === 'ACTIVE')
    .flatMap((r) => r['allocations'] as Json[]).filter((a) => a['invoiceId'] === inv['id']).reduce((s, a) => s + Number(a['amount']), 0));
  const outstanding = (inv: Json) => inv['status'] === 'ISSUED' && inv['docType'] === 'INVOICE' ? round(Number(inv['grossAmount']) - received(inv)) : 0;
  const blockers = (inv: Json) => [
    ...(!(inv['lines'] as Json[]).length ? [{ code: 'lines', message: 'Add at least one line.' }] : []),
    ...(!inv['dueDate'] ? [{ code: 'dueDate', message: 'Enter the due date.' }] : []),
  ];
  const view = (inv: Json) => {
    const actions: string[] = [];
    if (inv['status'] === 'DRAFT' && can('FIN.INVOICE.WRITE')) actions.push('EDIT', 'SUBMIT');
    if (inv['status'] === 'SUBMITTED' && can('FIN.INVOICE.APPROVE')) { if (inv['submittedBy'] !== user) actions.push('APPROVE'); actions.push('RETURN'); }
    if (inv['status'] === 'APPROVED' && can('FIN.INVOICE.WRITE')) actions.push('ISSUE');
    if (inv['status'] === 'ISSUED' && can('FIN.INVOICE.WRITE') && outstanding(inv) > 0) actions.push('CREDIT');
    return { ...inv, creditedAmount: 0, receivedAmount: received(inv), outstandingAmount: outstanding(inv),
      blockers: inv['status'] === 'DRAFT' ? blockers(inv) : [], allowedActions: actions,
      submittedByName: inv['submittedBy'] ? names[inv['submittedBy'] as string] : null };
  };
  const summary = (inv: Json) => ({ id: inv['id'], docType: inv['docType'], number: inv['number'], status: inv['status'], jobId: 'job-1', jobNumber: JOB,
    clientName: CLIENT, currency: 'USD', invoiceDate: inv['invoiceDate'], dueDate: inv['dueDate'], grossAmount: inv['grossAmount'],
    outstandingAmount: outstanding(inv), overdueDays: null, minorUnits: 2 });
  const touch = (o: Json, changes: Json) => Object.assign(o, changes, { version: (o['version'] as number) + 1 });
  const receiptView = (r: Json) => {
    const allocated = round((r['allocations'] as Json[]).filter((a) => !a['reversed']).reduce((s, a) => s + Number(a['amount']), 0));
    return { ...r, allocatedAmount: allocated, onAccountAmount: r['status'] === 'ACTIVE' ? round(Number(r['amount']) - allocated) : 0,
      canWrite: r['status'] === 'ACTIVE' && can('FIN.RECEIPT.WRITE'), minorUnits: 2 };
  };

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/job-1/invoices', async (route) => {
    if (route.request().method() === 'GET') {
      return json(route, 200, { jobId: 'job-1', jobNumber: JOB, currency: 'USD', invoices: invoices.map(summary), canCreate: can('FIN.INVOICE.WRITE'),
        hasAcceptedQuotation: true });
    }
    const body = route.request().postDataJSON() as Json;
    const inv: Json = {
      id: `inv-${invoices.length + 1}`, docType: 'INVOICE', number: null, status: 'DRAFT', jobId: 'job-1', jobNumber: JOB, 
      clientId: 'cl-1', clientName: CLIENT, creditedInvoiceId: null, creditedInvoiceNumber: null, currency: 'USD', minorUnits: 2, invoiceDate: '2026-10-18',
      dueDate: null, billingName: CLIENT, billingAddress: 'Synthetic address', paymentTerms: 'Synthetic: 30 days from invoice', notes: null,
      lines: body['fromQuotation'] ? priced([{ source: 'QUOTATION', costHeadCode: 'STS-SERVICE', description: null, quantity: '1', unitPrice: '10000' }]) : [],
      submittedBy: null, submittedAt: null, decidedByName: null, decidedAt: null, returnedReason: null, issuedByName: null, issuedAt: null, pdfSha256: null,
      version: 0,
    };
    totals(inv);
    invoices.push(inv);
    return json(route, 201, view(inv));
  });

  await page.route('**/api/v1/finance/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api/v1/finance', '');
    const method = route.request().method();
    const body = () => route.request().postDataJSON() as Json;

    if (path === '/invoices/options') {
      return json(route, 200, { costHeads: [{ code: 'STS-SERVICE', name: 'STS service charge', kind: 'REVENUE' }] });
    }
    if (path === '/invoices' && method === 'GET') {
      const status = url.searchParams.get('status');
      const items = invoices.filter((i) => !status || i['status'] === status).map(summary);
      return json(route, 200, { items, page: 0, size: 25, totalElements: items.length });
    }
    const m = path.match(/^\/invoices\/([\w-]+)(?:\/([\w-]+))?$/);
    if (m) {
      const inv = invoices.find((i) => i['id'] === m[1]);
      if (!inv) return json(route, 404, { status: 404, code: 'NOT_FOUND' });
      const action = m[2];
      if (!action && method === 'GET') return json(route, 200, view(inv));
      if (!action && method === 'PUT') {
        const b = body();
        state.saves.push(b);
        touch(inv, { ...b, lines: priced(b['lines'] as Json[]) });
        totals(inv);
        return json(route, 200, view(inv));
      }
      if (action === 'submit') {
        if (blockers(inv).length) return json(route, 400, { status: 400, code: 'INVOICE_INCOMPLETE' });
        return json(route, 200, view(touch(inv, { status: 'SUBMITTED', submittedBy: user, submittedAt: now })));
      }
      if (action === 'decision') {
        const b = body();
        state.decisions.push(b);
        return json(route, 200, view(touch(inv, b['decision'] === 'APPROVE' ? { status: 'APPROVED', decidedByName: names[user], decidedAt: now }
          : { status: 'DRAFT', returnedReason: b['reason'] })));
      }
      if (action === 'issue') {
        sequence += 1;
        return json(route, 200, view(touch(inv, { status: 'ISSUED', number: `INV/2026/${String(sequence).padStart(5, '0')}`, issuedByName: names[user],
          issuedAt: now, pdfSha256: 'e'.repeat(64) })));
      }
      return route.fallback();
    }

    const open = () => invoices.filter((i) => outstanding(i) > 0).map(summary);
    if (path === '/receipts/parties') {
      const total = round(invoices.reduce((s, i) => s + outstanding(i), 0));
      return json(route, 200, total > 0 ? [{ clientId: 'cl-1', clientName: CLIENT, currency: 'USD',
        outstandingAmount: total, minorUnits: 2 }] : []);
    }
    if (path === '/receipts/open-invoices') return json(route, 200, open());
    if (path === '/receipts' && method === 'GET') {
      return json(route, 200, { items: [...receipts].reverse().map(receiptView), page: 0, size: 25, totalElements: receipts.length });
    }
    if (path === '/receipts' && method === 'POST') {
      const b = body();
      state.receipts.push(b);
      const r: Json = { id: `rc-${receipts.length + 1}`, clientId: b['clientId'], clientName: CLIENT, receivedOn: b['receivedOn'],
        amount: Number(b['amount']), currency: b['currency'], method: b['method'], reference: b['reference'], status: 'ACTIVE',
        allocations: (b['allocations'] as Json[]).map((a, i) => ({ id: `al-${i}`, invoiceId: a['invoiceId'],
          invoiceNumber: invoices.find((x) => x['id'] === a['invoiceId'])?.['number'], amount: Number(a['amount']), reversed: false })),
        recordedByName: names[user], recordedAt: now, reversalReason: null, version: 0 };
      receipts.push(r);
      return json(route, 201, receiptView(r));
    }
    const rm = path.match(/^\/receipts\/([\w-]+)\/reverse$/);
    if (rm) {
      const r = receipts.find((x) => x['id'] === rm[1])!;
      touch(r, { status: 'REVERSED', reversalReason: body()['reason'] });
      (r['allocations'] as Json[]).forEach((a) => (a['reversed'] = true));
      return json(route, 200, receiptView(r));
    }
    if (path === '/receivables' || path === '/receivables/summary') {
      const rows = invoices.filter((i) => outstanding(i) > 0).map((i) => ({ invoiceId: i['id'], number: i['number'], clientId: 'cl-1', clientName: CLIENT,
        jobNumber: JOB, currency: 'USD', invoiceDate: i['invoiceDate'], dueDate: i['dueDate'], grossAmount: i['grossAmount'], creditedAmount: 0,
        receivedAmount: received(i), outstandingAmount: outstanding(i), overdueDays: 0, bucket: 'NOT_DUE', minorUnits: 2 }));
      const total = round(rows.reduce((s, r) => s + r.outstandingAmount, 0));
      return json(route, 200, { asOf: url.searchParams.get('asOf') ?? '2026-10-18', ...(path === '/receivables' ? { rows } : {}),
        totals: rows.length ? [{ clientName: CLIENT, currency: 'USD', buckets: { NOT_DUE: total, D1_30: 0, D31_60: 0, D61_90: 0, D90_PLUS: 0 }, total, minorUnits: 2 }] : [] });
    }
    return route.fallback();
  });

  return state;
}

export interface JobFinanceMock { costs: Json[]; actions: Json[]; extraHours: Json[] }

/** Job costs, FDA preview and the cross-job finance lists (Phase 5), synthetic data only. */
export async function mockJobFinanceApi(page: Page): Promise<JobFinanceMock> {
  const state: JobFinanceMock = { costs: [], actions: [], extraHours: [] };
  let user = 'finance';
  const docs: Json[] = [];
  let poSequence = 0;
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const gross = (d: Json) => (d['lines'] as Json[]).reduce((s, l) => s + Number(l['quantity']) * Number(l['unitPrice']), 0);
  const view = (d: Json) => ({ ...d, jobNumber: JOB, vendorName: 'Synthetic Tug Co', baseCurrency: 'INR', jobCurrency: 'USD', baseRate: '83', jobRate: '1',
    baseRateDate: null, baseRateSource: null, jobRateDate: null, jobRateSource: null, netAmount: gross(d), taxAmount: 0, grossAmount: gross(d),
    baseAmount: gross(d) * 83, jobAmount: gross(d), warnings: [], decisionReason: d['decisionReason'] ?? null,
    lines: (d['lines'] as Json[]).map((l) => ({ ...l, costHeadName: 'Tugboats', netAmount: Number(l['quantity']) * Number(l['unitPrice']), taxRate: 0, taxAmount: 0 })) });
  const summary = () => {
    const approved = docs.filter((d) => d['status'] === 'APPROVED');
    const committed = approved.filter((d) => d['kind'] === 'PO').reduce((s, d) => s + gross(d), 0);
    const actual = approved.filter((d) => d['kind'] === 'BILL').reduce((s, d) => s + gross(d), 0);
    return { jobId: 'job-1', jobNumber: JOB, currency: 'USD', budget: '150.00', committed: committed.toFixed(2), actualNet: actual.toFixed(2), actualTax: '0.00',
      actualGross: actual.toFixed(2), variance: (actual - 150).toFixed(2), warnings: [],
      heads: [{ code: 'TUGS', name: 'Tugboats', budget: '150.00', committed: committed.toFixed(2), actualNet: actual.toFixed(2), actualTax: '0.00',
        variance: (actual - 150).toFixed(2), varianceToCommitment: (actual - Math.max(150, committed)).toFixed(2) }] };
  };

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/job-1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api/v1/jobs/job-1', '');
    const method = route.request().method();
    if (path === '/costs' && method === 'GET') return json(route, 200, { items: [...docs].reverse().map(view), page: 0, size: 25, totalElements: docs.length });
    if (path === '/costs/summary') return json(route, 200, summary());
    if (path === '/costs/options') {
      return json(route, 200, { vendors: [{ id: 'v-1', name: 'Synthetic Tug Co' }], fx: [], heads: [{ code: 'TUGS', name: 'Tugboats' }],
        currencies: [{ code: 'USD', name: 'US Dollar', minorUnits: 2 }, { code: 'INR', name: 'Indian Rupee', minorUnits: 2 }],
        purchaseOrders: docs.filter((d) => d['kind'] === 'PO' && d['status'] === 'APPROVED')
          .map((d) => ({ id: d['id'], number: d['number'], reference: d['reference'], vendorId: 'v-1' })),
        jobCurrency: 'USD', baseCurrency: 'INR' });
    }
    if (path === '/costs/files') return json(route, 200, { id: 'file-1', originalName: 'vendor.pdf' });
    if (path === '/costs' && method === 'POST') {
      const body = route.request().postDataJSON() as Json;
      state.costs.push(body);
      const doc: Json = { ...body, id: `cost-${docs.length + 1}`, number: null, status: 'DRAFT', submittedBy: null, version: 0 };
      docs.push(doc);
      return json(route, 201, view(doc));
    }
    const action = path.match(/^\/costs\/([\w-]+)\/actions$/);
    if (action) {
      const doc = docs.find((d) => d['id'] === action[1])!;
      const body = route.request().postDataJSON() as Json;
      state.actions.push(body);
      const decision = body['decision'];
      if (decision === 'SUBMIT') Object.assign(doc, { status: 'SUBMITTED', submittedBy: `user-${user}` });
      if (decision === 'APPROVE') {
        doc['status'] = 'APPROVED';
        if (doc['kind'] === 'PO') doc['number'] = `PO/2026/${String(++poSequence).padStart(5, '0')}`;
      }
      if (decision === 'CLOSE') Object.assign(doc, { status: 'CLOSED', decisionReason: body['reason'] });
      doc['version'] = (doc['version'] as number) + 1;
      return json(route, 200, view(doc));
    }
    // The 48-hour clock is final and 4.5 hours over; the confirmed tariff prices the extra hours.
    if (path === '/movements/clock') {
      return json(route, 200, { exceeded: true, notice: { status: 'DRAFT', deliveryOutcome: null }, clock: { startAt: '2026-10-18T06:00:00Z', stopAt: '2026-10-20T10:30:00Z', basisHours: 48,
        elapsedHours: 52.5, extraHours: 4.5, tariffTermsStatus: 'CONFIRMED', billableExtraUnits: 5, additionalCharge: 1250, currency: 'USD', final: true } });
    }
    if (path === '/invoices/extra-hours' && method === 'POST') {
      state.extraHours.push(route.request().postDataJSON() as Json);
      return json(route, 200, { id: 'inv-9' });
    }
    if (path === '/fda') return json(route, 200, []);
    if (path === '/fda/preview') return json(route, 200, { snapshot: { income: '250.00', expenses: '100.00', received: '0.00', outstanding: '250.00', margin: '150.00',
      billing: [{ status: 'ISSUED' }, { status: 'DRAFT' }], bills: [{ id: 'bill-1' }] }, clientOnAccount: '0.00' });
    return route.fallback();
  });

  await page.route('**/api/v1/finance/invoices/options', async (route) => json(route, 200, {
    costHeads: [{ code: 'STS-SERVICE', name: 'STS service charge', kind: 'REVENUE' }, { code: 'TUGS', name: 'Tugboats', kind: 'EXPENSE' }] }));

  await page.route('**/api/v1/finance/cost-documents**', async (route) => {
    const kind = new URL(route.request().url()).searchParams.get('kind');
    const items = docs.filter((d) => d['kind'] === kind).map((d) => ({ id: d['id'], jobId: 'job-1', jobNumber: JOB, kind: d['kind'], number: d['number'],
      reference: d['reference'], vendorName: 'Synthetic Tug Co', status: d['status'], documentDate: d['documentDate'], dueDate: null, currency: 'USD',
      grossAmount: gross(d), jobCurrency: 'USD', jobAmount: gross(d) }));
    return json(route, 200, { items, page: 0, size: 25, totalElements: items.length });
  });

  await page.route('**/api/v1/finance/profitability**', async (route) => json(route, 200, { items: [{ jobId: 'job-1', jobNumber: JOB, clientName: CLIENT,
    status: 'OPEN', currency: 'USD', minorUnits: 2, jobDate: '2026-10-18', incomeNet: 10000, costNet: 2500, adjustmentsNet: 0, margin: 7500, marginPercent: 75,
    fdaIssued: false }], page: 0, size: 25, totalElements: 1 }));

  return state;
}
