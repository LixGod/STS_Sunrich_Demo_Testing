import { Page, Route } from '@playwright/test';
import { MockUser } from './mock-api';

/** In-browser stand-in for the live operations endpoints (Phase 4b), synthetic data only. */
export const OPERATOR: MockUser = {
  username: 'ops.mumbai', password: 'ops', fullName: 'Demo Operations (Mumbai)',
  permissions: ['JOB.READ', 'OPS.LIVE.READ', 'OPS.LIVE.WRITE', 'OPS.LIVE.APPROVE', 'OPS.CARGO.WRITE', 'OPS.CARGO.CORRECT', 'OPS.INCIDENT.READ',
    'OPS.INCIDENT.WRITE', 'OPS.INCIDENT.REVIEW', 'MASTER.OPERATIONS.WRITE'],
  grants: [{ roleCode: 'OPERATIONS', branchId: 'br-1' }],
};

export const REVIEWER: MockUser = {
  username: 'ops.manager', password: 'ops', fullName: 'Demo Operations Manager',
  permissions: OPERATOR.permissions,
  grants: [{ roleCode: 'OPERATIONS', branchId: null }],
};

const now = '2026-10-18T05:00:00Z';
type Json = Record<string, unknown>;

export interface OperationsMock { events: Json[]; readings: Json[]; incidents: Json[]; reviews: Json[]; listSaves: Json[] }

export async function mockOperationsApi(page: Page): Promise<OperationsMock> {
  const state: OperationsMock = { events: [], readings: [], incidents: [], reviews: [], listSaves: [] };
  let user = 'ops.mumbai';
  const names: Record<string, string> = { 'ops.mumbai': OPERATOR.fullName, 'ops.manager': REVIEWER.fullName };
  const eventTypes: Json[] = [
    ['POAC_BOARDED', 'POAC boarded', 'BERTHING', false], ['ALL_FAST', 'All fast', 'BERTHING', false],
    ['HOSE_CONNECTION', 'Hose connection', 'TRANSFER', true], ['CARGO_COMMENCED', 'Cargo transfer commenced', 'TRANSFER', false],
    ['OTHER', 'Other event', 'OTHER', false],
  ].map(([code, label, phase, interval], i) => ({ code, label, phase, interval, sortOrder: (i + 1) * 10, active: true, version: 0 }));
  const lists: Record<string, Json[]> = {
    'event-types': eventTypes,
    'delay-categories': [{ code: 'WEATHER', label: 'Weather', sortOrder: 10, active: true, version: 0 }],
    'incident-categories': [{ code: 'NEAR_MISS', label: 'Near miss', sortOrder: 10, active: true, version: 0 }],
    'incident-severities': [{ code: 'LOW', label: 'Low', sortOrder: 10, active: true, version: 0 }],
  };
  const events: Json[] = [];
  const readings: Json[] = [];
  const delays: Json[] = [];
  const incidents: Json[] = [];
  const sofs: Json[] = [];
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const typeOf = (code: string) => eventTypes.find((t) => t['code'] === code)!;
  const can = (perm: string) => [OPERATOR, REVIEWER].find((u) => u.username === user)!.permissions.includes(perm);

  const grade = () => {
    const cumulative = readings.length ? Number(readings.at(-1)!['cumulative']) : null;
    return { lineNo: 1, gradeCode: 'PROPANE', gradeName: 'Propane', unitCode: 'MT', planned: 3000, cumulative, balance: 3000 - (cumulative ?? 0),
      lastRatePerHour: null, estimatedCompletion: null, completed: false,
      readings: readings.map((r, i) => ({ ...r, quantitySincePrevious: i ? Number(r['cumulative']) - Number(readings[i - 1]['cumulative']) : null,
        ratePerHour: null })) };
  };
  const incidentView = (i: Json) => ({ ...i, reportedByName: names[i['reportedBy'] as string],
    canReview: can('OPS.INCIDENT.REVIEW') && i['status'] === 'REPORTED' && i['reportedBy'] !== user,
    canClose: can('OPS.INCIDENT.REVIEW') && i['status'] !== 'CLOSED' && i['reportedBy'] !== user });
  const sofView = (s: Json) => ({ ...s, preparedByName: names[s['preparedBy'] as string],
    canIssue: s['status'] === 'PREPARED' && can('OPS.LIVE.APPROVE') && s['preparedBy'] !== user,
    canWithdraw: s['status'] === 'PREPARED' && can('OPS.LIVE.WRITE') });
  const overview = () => ({
    jobId: 'job-1', jobNumber: 'JOB/2026/00001', timezone: 'Asia/Kolkata',
    vessels: [
      { jobVesselId: 'jv-1', role: 'MOTHER', name: 'DEMO MOTHER', current: { revisionNo: 1, etaUtc: '2026-10-18T02:00:00Z', timezone: 'Asia/Kolkata',
        source: 'AGENT', note: null, recordedByName: OPERATOR.fullName, recordedAt: now, changeHours: null }, history: [] },
      { jobVesselId: 'jv-2', role: 'DAUGHTER', name: 'DEMO DAUGHTER', current: null, history: [] },
    ],
    eventTypes, events: [...events].sort((a, b) => String(a['occurredAt'] ?? a['startedAt']).localeCompare(String(b['occurredAt'] ?? b['startedAt']))),
    cargo: [grade()], delays, incidents: incidents.map(incidentView),
    delayCategories: lists['delay-categories'], incidentCategories: lists['incident-categories'], incidentSeverities: lists['incident-severities'],
    sofRevisions: [...sofs].reverse().map(sofView),
    permissions: { write: can('OPS.LIVE.WRITE'), approve: can('OPS.LIVE.APPROVE'), cargoWrite: can('OPS.CARGO.WRITE'),
      cargoCorrect: can('OPS.CARGO.CORRECT'), incidentRead: can('OPS.INCIDENT.READ'), incidentWrite: can('OPS.INCIDENT.WRITE'),
      incidentReview: can('OPS.INCIDENT.REVIEW') },
  });

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/operations/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/operations/jobs') {
      return json(route, 200, { items: [{ jobId: 'job-1', jobNumber: 'JOB/2026/00001', status: 'OPEN', motherVessel: 'DEMO MOTHER',
        daughterVessel: 'DEMO DAUGHTER', lastEventLabel: events.length ? 'All fast' : null, lastEventAt: events.length ? now : null,
        openIncidents: incidents.filter((i) => i['status'] !== 'CLOSED').length }], page: 0, size: 50, totalElements: 1 });
    }
    const list = path.match(/^\/operations\/lists\/([\w-]+)(?:\/(\w+))?$/);
    if (list) {
      if (method === 'GET') return json(route, 200, lists[list[1]]);
      const body = route.request().postDataJSON() as Json;
      state.listSaves.push(body);
      const item = { ...body, version: 0 };
      lists[list[1]].push(item);
      return json(route, 201, item);
    }
    return route.fallback();
  });

  await page.route('**/api/v1/jobs/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const method = route.request().method();
    if (path === '/jobs/job-1/operations') return json(route, 200, overview());
    if (path === '/jobs/job-1/operations/events' && method === 'POST') {
      const body = route.request().postDataJSON() as Json;
      state.events.push(body);
      const type = typeOf(body['typeCode'] as string);
      const event = { ...body, id: `ev-${events.length + 1}`, typeLabel: type['label'], phase: type['phase'], interval: type['interval'],
        status: 'ACTIVE', voidReason: null, recordedByName: names[user], recordedAt: now, corrected: false, version: 0 };
      events.push(event);
      return json(route, 201, event);
    }
    const ev = path.match(/^\/jobs\/job-1\/operations\/events\/([\w-]+)$/);
    if (ev && method === 'PUT') {
      const body = route.request().postDataJSON() as Json;
      state.events.push(body);
      const event = events.find((e) => e['id'] === ev[1])!;
      Object.assign(event, body, { corrected: true, version: (event['version'] as number) + 1 });
      return json(route, 200, event);
    }
    const reading = path.match(/^\/jobs\/job-1\/operations\/cargo\/1\/readings$/);
    if (reading) {
      const body = route.request().postDataJSON() as Json;
      state.readings.push(body);
      const last = readings.at(-1);
      if (last && body['kind'] !== 'CORRECTION' && Number(body['cumulative']) < Number(last['cumulative'])) {
        return json(route, 422, { status: 422, code: 'CARGO_TOTAL_FALLING' });
      }
      readings.push({ id: `rd-${readings.length + 1}`, readAt: body['readAt'], cumulative: Number(body['cumulative']), kind: body['kind'],
        reason: body['reason'], certifiedDocumentId: null, remarks: body['remarks'], recordedByName: names[user], recordedAt: now });
      return json(route, 200, grade());
    }
    if (path === '/jobs/job-1/operations/incidents' && method === 'POST') {
      const body = route.request().postDataJSON() as Json;
      state.incidents.push(body);
      const incident = { ...body, id: `in-${incidents.length + 1}`, categoryLabel: 'Near miss', severityLabel: 'Low', status: 'REPORTED',
        reportedBy: user, reportedAt: now, reviewStartedByName: null, reviewStartedAt: null, closedByName: null, closedAt: null, closingNote: null,
        actions: [], files: [], corrected: false, version: 0 };
      incidents.push(incident);
      return json(route, 201, incidentView(incident));
    }
    const review = path.match(/^\/jobs\/job-1\/operations\/incidents\/([\w-]+)\/review$/);
    if (review) {
      const body = route.request().postDataJSON() as { decision: string; note: string | null };
      state.reviews.push(body);
      const incident = incidents.find((i) => i['id'] === review[1])!;
      Object.assign(incident, body.decision === 'CLOSE'
        ? { status: 'CLOSED', closedByName: names[user], closedAt: now, closingNote: body.note }
        : { status: 'UNDER_REVIEW', reviewStartedByName: names[user], reviewStartedAt: now }, { version: (incident['version'] as number) + 1 });
      return json(route, 200, incidentView(incident));
    }
    if (path === '/jobs/job-1/operations/sof' && method === 'POST') {
      sofs.push({ id: `sof-${sofs.length + 1}`, revisionNo: sofs.length + 1, status: 'PREPARED', eventCount: events.length, preparedBy: user,
        preparedAt: now, issuedByName: null, issuedAt: null, pdfSha256: null, version: 0 });
      return json(route, 200, [...sofs].reverse().map(sofView));
    }
    const sof = path.match(/^\/jobs\/job-1\/operations\/sof\/([\w-]+)\/issue$/);
    if (sof) {
      const s = sofs.find((x) => x['id'] === sof[1])!;
      if (s['preparedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_APPROVAL_FORBIDDEN' });
      Object.assign(s, { status: 'ISSUED', issuedByName: names[user], issuedAt: now, pdfSha256: 'b'.repeat(64), version: 1 });
      return json(route, 200, [...sofs].reverse().map(sofView));
    }
    return route.fallback();
  });

  return state;
}
