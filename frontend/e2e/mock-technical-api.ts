import { Page, Route } from '@playwright/test';

/**
 * Vessel documents, files and particulars for the technical screens, layered over `mockApi`. Mirrors the server rules
 * the screens rely on (no self-verification, every value confirmed, LOA check); the rules themselves are covered by the
 * Spring integration tests. Synthetic data only.
 */
export interface TechnicalMock {
  uploads: number;
  saves: { values: { fieldCode: string; value: string; sourceDocumentId: string | null; sourcePage: number | null }[] }[];
  verified: string[][];
}

type Json = Record<string, unknown>;

export const SAMPLE_PDF = Buffer.from('%PDF-1.4\n% synthetic Q88\n%%EOF\n');

export async function mockTechnicalApi(page: Page): Promise<TechnicalMock> {
  const state: TechnicalMock = { uploads: 0, saves: [], verified: [] };
  let user = '';
  const vessel: Json = { id: 'v-1', imo: '1000007', name: 'DEMO MOTHER', vesselType: 'LPG_CARRIER', loaM: 230, beamM: 36.6, dwtT: null,
    particularsStatus: 'UNVERIFIED', active: true, version: 0 };
  const documents: Json[] = [];
  let particulars: Json | null = null;

  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });

  const fields = [
    { code: 'LOA', label: 'Length overall (LOA)', group: 'DIMENSIONS', dataType: 'NUMBER', unit: 'm', requiredForPlanning: true },
    { code: 'BEAM', label: 'Beam', group: 'DIMENSIONS', dataType: 'NUMBER', unit: 'm', requiredForPlanning: true },
    { code: 'BOW_TO_MANIFOLD', label: 'Bow to centre manifold', group: 'MANIFOLD', dataType: 'NUMBER', unit: 'm', requiredForPlanning: true },
    { code: 'STERN_TO_MANIFOLD', label: 'Stern to centre manifold', group: 'MANIFOLD', dataType: 'NUMBER', unit: 'm', requiredForPlanning: true },
  ];

  const value = (code: string) => (particulars?.['values'] as Json[]).find((v) => v['fieldCode'] === code)?.['value'] as string | undefined;

  const view = () => {
    const p = particulars!;
    const warnings: Json[] = [];
    const missing = fields.filter((f) => f.requiredForPlanning && !value(f.code));
    if (missing.length) warnings.push({ code: 'MISSING_FOR_PLANNING', fields: missing.map((f) => f.code), message: 'Needed for the JPO and mooring plan.' });
    const [loa, bow, stern] = ['LOA', 'BOW_TO_MANIFOLD', 'STERN_TO_MANIFOLD'].map((c) => value(c));
    if (loa && bow && stern && Math.round((Number(bow) + Number(stern)) * 1000) !== Math.round(Number(loa) * 1000)) {
      warnings.push({ code: 'LOA_MISMATCH', fields: ['LOA', 'BOW_TO_MANIFOLD', 'STERN_TO_MANIFOLD'],
        message: `Bow to manifold + stern to manifold = ${(Number(bow) + Number(stern)).toFixed(2)} m, but LOA is ${loa} m.` });
    }
    const actions = p['status'] === 'DRAFT' ? ['EDIT', 'SUBMIT']
      : p['status'] === 'SUBMITTED' ? (p['submittedBy'] === user ? ['RETURN'] : ['RETURN', 'VERIFY']) : [];
    return { ...p, warnings, allowedActions: actions };
  };

  await page.route('**/api/v1/auth/login', async (route) => {
    user = (route.request().postDataJSON() as { username: string }).username;
    return route.fallback();
  });

  await page.route('**/api/v1/masters/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1/masters', '');
    if (path === '/vessels') return json(route, 200, { items: [vessel], page: 0, size: 25, totalElements: 1 });
    if (path === '/vessels/v-1') return json(route, 200, vessel);
    if (path === '/document-types') {
      return json(route, 200, { items: [{ id: 'dt-1', code: 'Q88', name: 'Q88 questionnaire', appliesTo: 'VESSEL', required: true, active: true }],
        page: 0, size: 100, totalElements: 1 });
    }
    return route.fallback();
  });

  await page.route(/\/api\/v1\/(technical|files)\//, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1', '');
    const method = request.method();

    if (path === '/technical/vessels/v-1/documents' && method === 'GET') return json(route, 200, documents);
    if (path === '/technical/vessels/v-1/documents' && method === 'POST') {
      state.uploads++;
      const document = { id: `doc-${documents.length + 1}`, documentTypeId: 'dt-1', documentTypeCode: 'Q88', documentTypeName: 'Q88 questionnaire',
        versionNo: documents.length + 1, latest: true, title: null,
        file: { id: `f-${documents.length + 1}`, name: 'q88.pdf', contentType: 'application/pdf', sizeBytes: SAMPLE_PDF.length, sha256: 'a'.repeat(64),
          scanState: 'NOT_SCANNED' },
        uploadedByName: 'Demo Technical Officer', uploadedAt: new Date().toISOString() };
      documents.forEach((d) => (d['latest'] = false));
      documents.unshift(document);
      return json(route, 201, document);
    }
    const token = path.match(/^\/files\/([^/]+)\/download-token$/);
    if (token) return json(route, 200, { token: 'tok', url: `/api/v1/files/download/tok${token[1]}`, expiresAt: new Date(Date.now() + 300_000).toISOString() });
    if (path.startsWith('/files/download/')) {
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: SAMPLE_PDF, headers: { 'X-Frame-Options': 'SAMEORIGIN' } });
    }

    if (path === '/technical/particular-fields') return json(route, 200, fields);
    if (path === '/technical/vessels/v-1/particulars' && method === 'GET') {
      return json(route, 200, { vesselId: 'v-1', versions: particulars ? [{ id: 'p-1', versionNo: 1, status: particulars['status'], verifiedAt: null }] : [],
        canCreateVersion: user === 'technical' && !particulars });
    }
    if (path === '/technical/vessels/v-1/particulars' && method === 'POST') {
      particulars = { id: 'p-1', vesselId: 'v-1', versionNo: 1, status: 'DRAFT', notes: null, values: [], submittedByName: null, submittedAt: null,
        verifiedByName: null, verifiedAt: null, returnedReason: null, version: 0 };
      return json(route, 201, view());
    }
    if (path === '/technical/particulars/p-1' && method === 'GET') return json(route, 200, view());
    if (path === '/technical/particulars/p-1' && method === 'PUT') {
      const body = request.postDataJSON() as TechnicalMock['saves'][number];
      state.saves.push(body);
      particulars!['values'] = body.values.map((v) => ({ ...v, verified: false }));
      particulars!['version'] = (particulars!['version'] as number) + 1;
      return json(route, 200, view());
    }
    if (path === '/technical/particulars/p-1/submit') {
      const missing = (particulars!['values'] as Json[]).filter((v) => !v['sourceDocumentId'] || !v['sourcePage']);
      if (missing.length) {
        return json(route, 400, { status: 400, code: 'SOURCE_REQUIRED', errors: missing.map((v) => ({ field: `values.${v['fieldCode']}`, code: 'sourceRequired' })) });
      }
      Object.assign(particulars!, { status: 'SUBMITTED', submittedBy: user, submittedByName: 'Demo Technical Officer', submittedAt: new Date().toISOString(),
        version: (particulars!['version'] as number) + 1 });
      return json(route, 200, view());
    }
    if (path === '/technical/particulars/p-1/verify') {
      if (particulars!['submittedBy'] === user) return json(route, 422, { status: 422, code: 'SELF_VERIFICATION_FORBIDDEN' });
      const { confirmedFieldCodes } = request.postDataJSON() as { confirmedFieldCodes: string[] };
      state.verified.push(confirmedFieldCodes);
      (particulars!['values'] as Json[]).forEach((v) => (v['verified'] = true));
      Object.assign(particulars!, { status: 'VERIFIED', verifiedByName: 'Demo Marine Superintendent', verifiedAt: new Date().toISOString(),
        version: (particulars!['version'] as number) + 1 });
      Object.assign(vessel, { particularsStatus: 'VERIFIED', loaM: Number(value('LOA')), beamM: Number(value('BEAM')) });
      return json(route, 200, view());
    }
    return json(route, 404, { status: 404, code: 'NOT_FOUND' });
  });

  return state;
}
