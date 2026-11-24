import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, shareReplay, switchMap } from 'rxjs';
import { API_BASE_URL, Page, QueryValue, toHttpParams } from '../api/api.models';
import { AuthService, CurrentUser } from '../auth/auth.service';

/** A picker option. `meta` carries extra facts the caller needs. */
export interface LookupOption {
  value: string;
  label: string;
  meta?: Record<string, string | null>;
}

interface Row {
  id: string;
  code?: string;
  name?: string;
  legalName?: string;
  imo?: string;
  fullName?: string;
  email?: string | null;
  countryCode?: string | null;
  phone?: string | null;
  phones?: string[];
  primary?: boolean;
  branchId?: string;
  branchCode?: string | null;
  timezone?: string;
  defaultTimezone?: string;
  defaultUnitId?: string | null;
  category?: string;
  kind?: string;
  appliesTo?: string;
  currency?: string;
  baseHours?: number;
  extraHourRate?: number;
  effectiveFrom?: string;
  effectiveTo?: string | null;
  active?: boolean;
}

/**
 * Read-only option lists from the masters APIs, for pickers in any feature (features must not import each other).
 * Searchable lists (clients, vessels) are fetched per query; short reference lists are cached for the session.
 */
@Injectable({ providedIn: 'root' })
export class LookupService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);
  private readonly cache = new Map<string, Observable<LookupOption[]>>();
  private cacheOwner: CurrentUser | null = null;

  /** Active clients matching the text (code or name), first 20. */
  searchClients(q: string): Observable<LookupOption[]> {
    return this.page('/masters/clients', { q, active: true, size: 20, sort: 'legalName,asc' }).pipe(
      map((rows) => rows.map((c) => ({ value: c.id, label: `${c.legalName} (${c.code})` }))),
    );
  }

  clientDetails(id: string): Observable<Pick<Row, 'countryCode' | 'phone' | 'phones'>> {
    return this.http.get<Row>(`${this.base}/masters/clients/${id}`);
  }

  /** Active vessels matching the name, former name or IMO, first 20. */
  searchVessels(q: string): Observable<LookupOption[]> {
    return this.page('/masters/vessels', { q, active: true, size: 20, sort: 'name,asc' }).pipe(
      map((rows) => rows.map((v) => ({ value: v.id, label: `${v.name} · IMO ${v.imo}`, meta: { imo: v.imo ?? null } }))),
    );
  }

  contacts(clientId: string): Observable<LookupOption[]> {
    return this.http.get<Row[]>(`${this.base}/masters/clients/${clientId}/contacts`).pipe(
      map((rows) => rows.filter((c) => c.active).map((c) => ({
        value: c.id,
        label: c.email ? `${c.fullName} <${c.email}>` : c.fullName!,
        meta: { primary: c.primary ? 'true' : 'false' },
      }))),
    );
  }

  branches(): Observable<LookupOption[]> {
    return this.cached('branches', '/masters/branches', (r) => ({
      value: r.id, label: `${r.code} — ${r.name}`,
      meta: { timezone: r.timezone ?? null },
    }));
  }

  cargoGrades(): Observable<LookupOption[]> {
    return this.cached('cargoGrades', '/masters/cargo-grades', (r) => ({
      value: r.id, label: `${r.code} — ${r.name}`, meta: { defaultUnitId: r.defaultUnitId ?? null, category: r.category ?? null },
    }));
  }

  operationTypes(): Observable<LookupOption[]> {
    return this.cached('operationTypes', '/masters/operation-types', (r) => ({ value: r.code!, label: r.name ?? r.code! }));
  }

  units(): Observable<LookupOption[]> {
    return this.cached('units', '/masters/units', (r) => ({ value: r.id, label: r.code! }));
  }

  currencies(): Observable<LookupOption[]> {
    return this.cached('currencies', '/masters/currencies', (r) => ({ value: r.code!, label: `${r.code} — ${r.name}` }));
  }

  /** Cost heads with their kind (EXPENSE, REVENUE or BOTH) in `meta.kind` and code in `meta.code`. */
  costHeads(): Observable<LookupOption[]> {
    return this.cached('costHeads', '/masters/cost-heads', (r) => ({
      value: r.id,
      label: `${r.code} — ${r.name}`,
      meta: { kind: r.kind ?? null, code: r.code ?? null },
    }));
  }

  /** Active document types for one kind of record (VESSEL, JOB, EQUIPMENT, PERSONNEL). */
  documentTypes(appliesTo: string): Observable<LookupOption[]> {
    return this.cached('documentTypes', '/masters/document-types', (r) => ({
      value: r.id, label: r.name ?? r.code!, meta: { appliesTo: r.appliesTo ?? null, code: r.code ?? null },
    })).pipe(map((list) => list.filter((o) => o.meta?.['appliesTo'] === appliesTo)));
  }

  /** Active tariff versions (not cached: they are effective-dated and edited by finance). */
  tariffs(): Observable<LookupOption[]> {
    return this.allPages('/masters/tariffs', { active: true, size: 100, sort: 'code,asc' }).pipe(
      map((rows) => rows.map((t) => ({
        value: t.id,
        label: `${t.code} — ${t.name} (${t.currency}, ${t.baseHours} h, from ${t.effectiveFrom})`,
        meta: { currency: t.currency ?? null, baseHours: String(t.baseHours ?? ''), extraHourRate: String(t.extraHourRate ?? ''),
          effectiveFrom: t.effectiveFrom ?? null, effectiveTo: t.effectiveTo ?? null },
      }))),
    );
  }

  /** Forget cached lists (e.g. after a master record was added). */
  invalidate(): void {
    this.cache.clear();
  }

  private cached(key: string, path: string, toOption: (row: Row) => LookupOption): Observable<LookupOption[]> {
    const owner = this.auth.currentUser();
    if (owner !== this.cacheOwner) { this.cache.clear(); this.cacheOwner = owner; }
    let list = this.cache.get(key);
    if (!list) {
      list = this.allPages(path, { active: true, size: 100, sort: 'code,asc' }).pipe(
        map((rows) => rows.map(toOption)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
      this.cache.set(key, list);
    }
    return list;
  }

  private allPages(path: string, params: Record<string, QueryValue>, page = 0, rows: Row[] = []): Observable<Row[]> {
    return this.http.get<Page<Row>>(`${this.base}${path}`, { params: toHttpParams({ ...params, page }) }).pipe(
      switchMap((result) => {
        const all = [...rows, ...result.items];
        return all.length < result.totalElements ? this.allPages(path, params, page + 1, all) : of(all);
      }),
    );
  }

  private page(path: string, params: Record<string, QueryValue>): Observable<Row[]> {
    return this.http.get<Page<Row>>(`${this.base}${path}`, { params: toHttpParams(params) }).pipe(map((page) => page.items));
  }
}
