import { Page, Route } from '@playwright/test';

/** In-browser stand-in for the operating-limits, weather and weather-parameter endpoints (Phase 3e), synthetic data only. */
const now = '2026-10-17T05:00:00Z';
const job = { id: 'job-1', jobNumber: 'JOB/2026/00001', status: 'OPEN', branchCode: 'MUM',
  enquiryId: 'enq-1', enquiryReference: 'ENQ/2026/00001', quotationReference: 'QUO/2026/00001',
  client: { id: 'client-1', code: 'DEMO', name: 'DEMO Charterer' }, contact: null, operationType: 'LPG',
  currency: 'USD', quotedAmount: 10000, vessels: [], cargo: [], tariff: null, createdAt: now };

type Json = Record<string, unknown>;

export interface WeatherMock { limitSaves: Json[]; reports: Json[]; reviews: Json[]; parameterSaves: Json[] }

export async function mockWeatherApi(page: Page): Promise<WeatherMock> {
  const state: WeatherMock = { limitSaves: [], reports: [], reviews: [], parameterSaves: [] };
  let user = 'technical';
  const names: Record<string, string> = { technical: 'Demo Technical Officer', marine: 'Demo Marine Superintendent' };
  const parameters: Json[] = [
    { code: 'WIND_SPEED', label: 'Wind speed', unit: 'kn', sortOrder: 10, active: true, version: 0 },
    { code: 'WAVE_HEIGHT', label: 'Significant wave height', unit: 'm', sortOrder: 20, active: true, version: 0 },
    { code: 'WAVE_PERIOD', label: 'Wave period', unit: 's', sortOrder: 30, active: true, version: 0 },
  ];
  const sets: Json[] = [];
  const reports: Json[] = [];
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const label = (code: string) => parameters.find((p) => p['code'] === code) as Json;
  const rowsOf = (rows: Json[]) => rows.map((r) => ({ ...r, parameterLabel: label(r['parameterCode'] as string)['label'],
    unit: label(r['parameterCode'] as string)['unit'] }));
  const setView = (s: Json) => {
    const actions: string[] = [];
    if (s['status'] === 'DRAFT') actions.push('EDIT', 'SUBMIT');
    if (s['status'] === 'SUBMITTED') { if (s['submittedBy'] !== user) actions.push('APPROVE'); actions.push('RETURN'); }
    return { ...s, rows: rowsOf(s['rows'] as Json[]), allowedActions: actions,
      submittedByName: s['submittedBy'] ? names[s['submittedBy'] as string] : null };
  };
  const approved = () => sets.find((s) => s['status'] === 'APPROVED');
  const touch = (o: Json, changes: Json) => Object.assign(o, changes, { version: (o['version'] as number) + 1 });
  const warnings = () => {
    const set = approved();
    if (!set) return [];
    return reports.filter((r) => r['status'] !== 'REJECTED').flatMap((r) => (r['values'] as Json[]).flatMap((v) =>
      (set['rows'] as Json[]).filter((l) => l['parameterCode'] === v['parameterCode'] &&
        (l['comparison'] === 'BELOW' ? Number(v['value']) < Number(l['threshold']) : Number(v['value']) > Number(l['threshold'])))
        .map((l) => ({ reportId: r['id'], kind: r['kind'], reportStatus: r['status'], at: v['at'], parameterCode: l['parameterCode'],
          parameterLabel: label(l['parameterCode'] as string)['label'], unit: label(l['parameterCode'] as string)['unit'], value: v['value'],
          comparison: l['comparison'], threshold: l['threshold'], operation: l['operation'], action: l['action'], message: '' }))));
  };
  const reportView = (r: Json) => ({ ...r, expired: false, canEdit: r['status'] === 'RECORDED',
    canReview: r['status'] === 'RECORDED' && r['recordedBy'] !== user, recordedByName: names[r['recordedBy'] as string] });

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/jobs/job-1' && method === 'GET') return json(route, 200, job);
    if (path === '/jobs/job-1/operating-limits') {
      return json(route, 200, { jobId: 'job-1', jobNumber: job.jobNumber,
        revisions: [...sets].reverse().map((s) => ({ id: s['id'], revisionNo: s['revisionNo'], status: s['status'], decidedAt: s['decidedAt'] })),
        approved: approved() ? setView(approved()!) : null, canCreate: !sets.some((s) => ['DRAFT', 'SUBMITTED'].includes(s['status'] as string)) });
    }
    if (path === '/jobs/job-1/operating-limits/revisions' && method === 'POST') {
      const set: Json = { id: `lim-${sets.length + 1}`, revisionNo: sets.length + 1, status: 'DRAFT', rows: [...((sets.at(-1)?.['rows'] as Json[]) ?? [])],
        submittedBy: null, submittedAt: null, decidedByName: null, decidedAt: null, returnedReason: null, updatedAt: now, version: 0 };
      sets.push(set);
      return json(route, 201, setView(set));
    }
    const limit = path.match(/^\/jobs\/job-1\/operating-limits\/revisions\/([^/]+)(?:\/(\w+))?$/);
    if (limit) {
      const set = sets.find((s) => s['id'] === limit[1])!;
      if (!limit[2] && method === 'GET') return json(route, 200, setView(set));
      if (!limit[2] && method === 'PUT') {
        const body = route.request().postDataJSON() as Json;
        state.limitSaves.push(body);
        return json(route, 200, setView(touch(set, { rows: body['rows'] })));
      }
      if (limit[2] === 'submit') return json(route, 200, setView(touch(set, { status: 'SUBMITTED', submittedBy: user, submittedAt: now })));
      if (limit[2] === 'decision') {
        const body = route.request().postDataJSON() as { decision: string; reason: string | null };
        if (body.decision === 'APPROVE') {
          if (set['submittedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
          sets.filter((s) => s['status'] === 'APPROVED').forEach((s) => (s['status'] = 'SUPERSEDED'));
          return json(route, 200, setView(touch(set, { status: 'APPROVED', decidedByName: names[user], decidedAt: now })));
        }
        return json(route, 200, setView(touch(set, { status: 'DRAFT', returnedReason: body.reason })));
      }
    }
    if (path === '/jobs/job-1/weather' && method === 'GET') {
      return json(route, 200, { jobId: 'job-1', jobNumber: job.jobNumber, timezone: 'Asia/Kolkata', parameters,
        reports: [...reports].reverse().map(reportView), warnings: warnings(), limitsApproved: !!approved(), canRecord: true });
    }
    if (path === '/jobs/job-1/weather/reports' && method === 'POST') {
      const raw = route.request().postDataBuffer()?.toString('utf8') ?? '';
      const part = raw.match(/name="report"[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([\s\S]*?)\r\n--/);
      const body = JSON.parse(part?.[1] ?? '{}') as Json;
      state.reports.push({ ...body, hasFile: raw.includes('name="file"') });
      const report: Json = { ...body, id: `wr-${reports.length + 1}`, file: null, status: 'RECORDED', recordedBy: user, recordedAt: now,
        reviewedByName: null, reviewedAt: null, reviewReason: null, version: 0 };
      reports.push(report);
      return json(route, 201, reportView(report));
    }
    const review = path.match(/^\/jobs\/job-1\/weather\/reports\/([^/]+)\/review$/);
    if (review) {
      const report = reports.find((r) => r['id'] === review[1])!;
      const body = route.request().postDataJSON() as { decision: string; reason: string | null };
      state.reviews.push(body);
      if (report['recordedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
      return json(route, 200, reportView(touch(report, { status: body.decision === 'ACCEPT' ? 'ACCEPTED' : 'REJECTED',
        reviewedByName: names[user], reviewedAt: now, reviewReason: body.reason })));
    }
    return route.fallback();
  });

  await page.route('**/api/v1/technical/weather-parameters**', async (route) => {
    const method = route.request().method();
    if (method === 'GET') return json(route, 200, parameters);
    const body = route.request().postDataJSON() as Json;
    state.parameterSaves.push(body);
    if (method === 'POST') {
      const created = { ...body, version: 0 };
      parameters.push(created);
      return json(route, 201, created);
    }
    return route.fallback();
  });

  return state;
}
