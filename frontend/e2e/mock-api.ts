import { Page, Route } from '@playwright/test';

/** Minimal in-browser stand-in for the Ship2Ship REST API, with synthetic data only. */
export interface MockUser {
  username: string;
  password: string;
  fullName: string;
  permissions: string[];
  grants: { roleCode: string; branchId: string | null }[];
}

export const ADMIN: MockUser = {
  username: 'admin',
  password: 'admin',
  fullName: 'Demo Administrator',
  permissions: ['ADMIN.USER.READ', 'ADMIN.USER.WRITE', 'ADMIN.ROLE.READ', 'MASTER.ORG.READ', 'MASTER.ORG.WRITE', 'AUDIT.READ',
    'JOB.READ', 'MASTER.RESOURCE.READ', 'MASTER.RESOURCE.WRITE', 'OPS.ALLOCATION.READ', 'OPS.ALLOCATION.WRITE'],
  grants: [{ roleCode: 'SYSTEM_ADMIN', branchId: null }],
};

export const STS_ADMIN: MockUser = {
  username: 'sts.admin', password: 'sts', fullName: 'Demo STS Admin',
  permissions: ['MASTER.ORG.READ', 'MASTER.ORG.WRITE', 'MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE',
    'MASTER.FINANCE.READ', 'MASTER.FINANCE.WRITE', 'MASTER.TECHNICAL.READ', 'MASTER.TECHNICAL.WRITE',
    'MASTER.RESOURCE.READ', 'MASTER.RESOURCE.WRITE', 'DOCUMENT.READ', 'DOCUMENT.WRITE'],
  grants: [{ roleCode: 'STS_ADMIN', branchId: null }],
};

export const OPS: MockUser = {
  username: 'ops.mumbai',
  password: 'ops',
  fullName: 'Demo Operations (Mumbai)',
  permissions: ['MASTER.ORG.READ', 'JOB.READ', 'MASTER.RESOURCE.READ', 'OPS.ALLOCATION.READ', 'OPS.ALLOCATION.WRITE', 'OPS.ALLOCATION.APPROVE'],
  grants: [{ roleCode: 'OPERATIONS', branchId: 'br-1' }],
};
ADMIN.permissions.push('OPS.MOVEMENT.READ', 'OPS.MOVEMENT.WRITE', 'OPS.RECOVERY.READ', 'OPS.RECOVERY.WRITE');
OPS.permissions.push('OPS.MOVEMENT.READ', 'OPS.MOVEMENT.WRITE', 'OPS.MOVEMENT.APPROVE',
  'OPS.RECOVERY.READ', 'OPS.RECOVERY.WRITE', 'OPS.RECOVERY.APPROVE');

export const OPS_MANAGER: MockUser = {
  ...OPS, username: 'ops.manager', fullName: 'Demo Operations Manager',
  grants: [{ roleCode: 'OPERATIONS', branchId: null }],
};

export const COMMERCIAL: MockUser = {
  username: 'commercial',
  password: 'commercial',
  fullName: 'Demo Commercial Executive',
  permissions: ['MASTER.ORG.READ', 'MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE', 'MASTER.FINANCE.READ',
    'COMMERCIAL.ENQUIRY.READ', 'COMMERCIAL.ENQUIRY.WRITE', 'COMMERCIAL.ESTIMATE.READ', 'COMMERCIAL.ESTIMATE.WRITE',
    'COMMERCIAL.QUOTATION.READ', 'COMMERCIAL.QUOTATION.WRITE', 'JOB.READ', 'MASTER.RESOURCE.READ', 'OPS.ALLOCATION.READ'],
  grants: [{ roleCode: 'COMMERCIAL', branchId: null }],
};

export const APPROVER: MockUser = {
  username: 'approver',
  password: 'approver',
  fullName: 'Demo Management Approver',
  permissions: ['MASTER.ORG.READ', 'MASTER.COMMERCIAL.READ', 'MASTER.FINANCE.READ', 'COMMERCIAL.ENQUIRY.READ', 'COMMERCIAL.ESTIMATE.READ',
    'COMMERCIAL.QUOTATION.READ', 'COMMERCIAL.QUOTATION.APPROVE', 'JOB.READ', 'AUDIT.READ'],
  grants: [{ roleCode: 'MANAGEMENT_APPROVER', branchId: null }],
};

const TECHNICAL_PERMISSIONS = ['MASTER.ORG.READ', 'MASTER.COMMERCIAL.READ', 'TECH.VESSEL.READ', 'TECH.VESSEL.WRITE', 'TECH.VESSEL.VERIFY',
  'DOCUMENT.READ', 'DOCUMENT.WRITE', 'MASTER.TECHNICAL.READ', 'MASTER.TECHNICAL.WRITE', 'JOB.READ',
  'TECH.JOB.READ', 'TECH.JOB.WRITE', 'TECH.JOB.REVIEW', 'TECH.JPO.READ', 'TECH.JPO.WRITE', 'TECH.JPO.APPROVE',
  'TECH.WEATHER.READ', 'TECH.WEATHER.WRITE', 'TECH.WEATHER.APPROVE'];
TECHNICAL_PERMISSIONS.push('TECH.MOORING.READ', 'TECH.MOORING.WRITE', 'TECH.MOORING.APPROVE');
TECHNICAL_PERMISSIONS.push('MASTER.RESOURCE.READ', 'MASTER.RESOURCE.WRITE', 'OPS.ALLOCATION.READ');

export const TECHNICAL: MockUser = {
  username: 'technical',
  password: 'technical',
  fullName: 'Demo Technical Officer',
  permissions: TECHNICAL_PERMISSIONS,
  grants: [{ roleCode: 'TECHNICAL', branchId: null }],
};

export const MARINE: MockUser = {
  username: 'marine',
  password: 'marine',
  fullName: 'Demo Marine Superintendent',
  permissions: TECHNICAL_PERMISSIONS,
  grants: [{ roleCode: 'TECHNICAL', branchId: null }],
};

interface Branch {
  id: string;
  code: string;
  name: string;
  timezone: string;
  address: string | null;
  active: boolean;
  updatedAt: string;
  version: number;
}

export async function mockApi(page: Page, users: MockUser[] = [ADMIN, OPS]) {
  let signedIn: MockUser | null = null;
  const branches: Branch[] = [
    { id: 'br-1', code: 'MUM', name: 'Demo Mumbai Branch', timezone: 'Asia/Kolkata', address: null, active: true,
      updatedAt: '2026-10-16T05:00:00Z', version: 0 },
  ];
  const audit: { seq: number; occurredAt: string; actorName: string; action: string; entityType: string; entityId: string; details: unknown }[] = [];
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const me = () => ({ id: `user-${signedIn!.username}`, workEmail: `${signedIn!.username}@demo.invalid`, language: 'en', ...signedIn,
    globalPermissions: signedIn!.grants.some(grant => grant.branchId === null) ? signedIn!.permissions : [] });

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace('/api/v1', '');
    const method = request.method();

    if (path === '/auth/login' && method === 'POST') {
      const { username, password } = request.postDataJSON();
      const user = users.find((u) => u.username === username && u.password === password);
      if (!user) return json(route, 401, { status: 401, code: 'INVALID_CREDENTIALS' });
      signedIn = user;
      return json(route, 200, me());
    }
    if (!signedIn) return json(route, 401, { status: 401, code: 'UNAUTHENTICATED' });
    if (path === '/me') return json(route, 200, me());
    if (path === '/auth/logout') {
      signedIn = null;
      return route.fulfill({ status: 204 });
    }
    if (path === '/jobs/status-counts' && method === 'GET') {
      if (!signedIn.permissions.includes('JOB.READ')) return json(route, 403, { status: 403, code: 'ACCESS_DENIED' });
      return json(route, 200, {});
    }
    if (/^\/jobs\/[^/]+\/workflow$/.test(path) && method === 'GET') {
      return json(route, 200, { status: 'OPEN', revision: 1, version: 0, readinessRequestedBy: null, closureRequestedBy: null,
        canRequest: false, canOverride: false, actions: [], history: [] });
    }

    const dashboardLists: Record<string, string> = {
      '/jobs': 'JOB.READ', '/enquiries': 'COMMERCIAL.ENQUIRY.READ',
      '/finance/invoices': 'FIN.INVOICE.READ', '/finance/receipts': 'FIN.RECEIPT.READ',
    };
    if (method === 'GET' && path in dashboardLists) {
      if (!signedIn.permissions.includes(dashboardLists[path])) return json(route, 403, { status: 403, code: 'ACCESS_DENIED' });
      return json(route, 200, { items: [], page: 0, size: Number(url.searchParams.get('size') ?? 25), totalElements: 0 });
    }
    if (path === '/quotation-versions/pending-approval' && method === 'GET') return json(route, 200, []);
    if (path === '/masters/currencies' && method === 'GET') {
      return json(route, 200, { items: [
        { code: 'INR', name: 'Indian rupee', active: true },
        { code: 'SGD', name: 'Singapore dollar', active: true },
      ], page: 0, size: 25, totalElements: 2 });
    }

    if (path === '/masters/branches' && method === 'GET') {
      const active = url.searchParams.get('active');
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const items = branches.filter(
        (e) => (active === null || String(e.active) === active) && (!q || `${e.code} ${e.name}`.toLowerCase().includes(q)),
      );
      return json(route, 200, { items, page: 0, size: Number(url.searchParams.get('size') ?? 25), totalElements: items.length });
    }
    if (path === '/masters/branches' && method === 'POST') {
      if (!signedIn.permissions.includes('MASTER.ORG.WRITE')) return json(route, 403, { status: 403, code: 'ACCESS_DENIED' });
      const body = request.postDataJSON();
      if (branches.some((e) => e.code === body.code)) {
        return json(route, 409, { status: 409, code: 'DUPLICATE_CODE', errors: [{ field: 'code', code: 'duplicate', message: 'This code is already in use.' }] });
      }
      const created: Branch = { ...body, id: `br-${branches.length + 1}`, active: true, updatedAt: new Date().toISOString(), version: 0 };
      branches.push(created);
      audit.unshift({ seq: audit.length + 1, occurredAt: new Date().toISOString(), actorName: signedIn.fullName, action: 'CREATE', entityType: 'BRANCH', entityId: created.id, details: body });
      return json(route, 201, created);
    }
    const match = path.match(/^\/masters\/branches\/([^/]+)$/);
    if (match && method === 'GET') {
      const entity = branches.find((e) => e.id === match[1]);
      return entity ? json(route, 200, entity) : json(route, 404, { status: 404, code: 'NOT_FOUND' });
    }
    if (path === '/audit-events') {
      const entityId = url.searchParams.get('entityId');
      return json(route, 200, { items: audit.filter((a) => !entityId || a.entityId === entityId), nextCursor: null });
    }
    // The dashboard's action inbox and key figures: empty unless a feature mock answers first.
    if (path === '/alerts' || path === '/jobs' || path === '/finance/invoices' || path === '/finance/cost-documents') {
      return json(route, 200, { items: [], page: 0, size: 25, totalElements: 0 });
    }
    if (path === '/admin/users') {
      return json(route, 200, { items: [], page: 0, size: 25, totalElements: 0 });
    }
    return json(route, 404, { status: 404, code: 'NOT_FOUND' });
  });
}
