import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, PageQuery, QueryValue, SILENT_ERRORS, ifMatch, pageParams, toHttpParams } from '../../../core/api/api.models';

export type EnquiryStatus = 'DRAFT' | 'COSTING' | 'QUOTATION_APPROVAL' | 'QUOTED' | 'ACCEPTED' | 'LOST' | 'CANCELLED';
export type OperationType = string;
export const ENQUIRY_STATUSES: readonly EnquiryStatus[] = ['DRAFT', 'COSTING', 'QUOTATION_APPROVAL', 'QUOTED', 'ACCEPTED', 'LOST', 'CANCELLED'];
export const OPERATION_TYPES: readonly OperationType[] = ['PETROLEUM', 'LPG', 'LNG', 'CHEMICAL', 'OTHER'];
/** Moves that must carry a reason (mirrors the server rule). */
export const REASON_REQUIRED: readonly EnquiryStatus[] = ['LOST', 'CANCELLED'];

export interface NamedRef {
  id: string;
  code: string | null;
  name: string;
}

export interface EnquiryVessel {
  role: 'MOTHER' | 'DAUGHTER';
  vesselId: string;
  imo: string | null;
  name: string | null;
  etaUtc: string | null;
  etaTimezone: string | null;
  etaLocal: string | null;
}

export interface EnquiryCargo {
  lineNo: number;
  cargoGradeId: string;
  cargoGradeCode: string | null;
  cargoGradeName: string | null;
  quantity: number;
  unitId: string;
  unitCode: string | null;
}

export interface EnquirySpecialItem {
  lineNo: number;
  description: string;
  paid: boolean;
  salePriceUsd: number | null;
}

export interface Transition {
  fromStatus: string | null;
  toStatus: string;
  reason: string | null;
  occurredAt: string;
}

export interface EnquiryDetail {
  id: string;
  reference: string;
  branchId: string;
  branchCode: string | null;
  status: EnquiryStatus;
  allowedTransitions: EnquiryStatus[];
  editable: boolean;
  receivedAt: string | null;
  client: NamedRef | null;
  contact: NamedRef | null;
  operationType: OperationType | null;
  cargoTypes: OperationType[];
  expectedDurationHours: number | null;
  scopeOfServices: string | null;
  specialRequirements: string | null;
  sourceReference: string | null;
  motherVessel: EnquiryVessel | null;
  daughterVessel: EnquiryVessel | null;
  cargo: EnquiryCargo[];
  specialItems: EnquirySpecialItem[];
  history: Transition[];
  version: number | null;
}

export interface EnquirySummary {
  id: string;
  reference: string;
  status: EnquiryStatus;
  receivedAt: string | null;
  branchCode: string | null;
  clientName: string | null;
  motherVesselName: string | null;
  daughterVesselName: string | null;
  cargoGrades: string[];
  jobNumber: string | null;
  clientStatus: string | null;
  estimatedCost: number | null;
  estimatedCurrency: string | null;
  jobCurrency: string | null;
  fdaStatus: string | null;
  expenses: number | null;
  grossProfit: number | null;
  grossProfitPercent: number | null;
  balanceReceivable: number | null;
}

export interface EnquiryVesselRequest {
  vesselId: string;
  /** Wall-clock time in the branch time zone, `YYYY-MM-DDTHH:mm:ss`; the server converts it to UTC. */
  etaLocal: string | null;
}

export interface EnquiryRequest {
  branchId: string;
  receivedAt: string | null;
  clientId: string | null;
  clientContactId: string | null;
  operationType: OperationType | null;
  cargoTypes: OperationType[];
  expectedDurationHours: string | null;
  scopeOfServices: string | null;
  specialRequirements: string | null;
  sourceReference: string | null;
  motherVessel: EnquiryVesselRequest | null;
  daughterVessel: EnquiryVesselRequest | null;
  cargo: { cargoGradeId: string; quantity: string; unitId: string }[];
  specialItems: { description: string; paid: boolean; salePriceUsd: string | null }[];
}

export interface EnquiryDraftRequest {
  branchId: string | null;
  receivedAt: string | null;
  clientId: string | null;
  clientLabel: string | null;
  clientContactId: string | null;
  operationType: OperationType | null;
  cargoTypes: OperationType[];
  expectedDurationHours: string | null;
  scopeOfServices: string | null;
  specialRequirements: string | null;
  sourceReference: string | null;
  motherVessel: { vesselId: string | null; etaLocal: string | null } | null;
  motherVesselLabel: string | null;
  daughterVessel: { vesselId: string | null; etaLocal: string | null } | null;
  daughterVesselLabel: string | null;
  cargo: { cargoGradeId: string | null; quantity: string | null; unitId: string | null }[];
  specialItems: { description: string; paid: boolean; salePriceUsd: string | null }[];
}

export interface EnquiryDraft {
  id: string;
  data: EnquiryDraftRequest;
  updatedAt: string | null;
  version: number | null;
}

@Injectable({ providedIn: 'root' })
export class EnquiryService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/enquiries`;
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  list(query: PageQuery): Observable<Page<EnquirySummary>> {
    return this.http.get<Page<EnquirySummary>>(this.url, { params: pageParams(query) });
  }

  xlsxUrl(filters: Record<string, QueryValue>): string {
    const query = toHttpParams(filters).toString();
    return query ? `${this.url}/xlsx?${query}` : `${this.url}/xlsx`;
  }

  get(id: string): Observable<EnquiryDetail> {
    return this.http.get<EnquiryDetail>(`${this.url}/${id}`);
  }

  /** The key makes a retried or double-clicked save return the enquiry created the first time. */
  create(request: EnquiryRequest, idempotencyKey: string): Observable<EnquiryDetail> {
    return this.http.post<EnquiryDetail>(this.url, request, { headers: { 'Idempotency-Key': idempotencyKey }, context: this.silent });
  }

  update(id: string, version: number | null, request: EnquiryRequest): Observable<EnquiryDetail> {
    return this.http.put<EnquiryDetail>(`${this.url}/${id}`, request, { headers: { 'If-Match': ifMatch(version) }, context: this.silent });
  }

  transition(id: string, version: number | null, to: EnquiryStatus, reason: string | null): Observable<EnquiryDetail> {
    return this.http.post<EnquiryDetail>(`${this.url}/${id}/transitions`, { to, reason }, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }

  duplicates(motherVesselId: string, daughterVesselId: string, excludeId: string | null): Observable<EnquirySummary[]> {
    return this.http.get<EnquirySummary[]>(`${this.url}/duplicates`, {
      params: toHttpParams({ motherVesselId, daughterVesselId, excludeId }),
      context: this.silent,
    });
  }
}

@Injectable({ providedIn: 'root' })
export class EnquiryDraftService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/enquiry-drafts`;

  list(): Observable<EnquiryDraft[]> { return this.http.get<EnquiryDraft[]>(this.url); }
  get(id: string): Observable<EnquiryDraft> { return this.http.get<EnquiryDraft>(`${this.url}/${id}`); }
  create(data: EnquiryDraftRequest): Observable<EnquiryDraft> { return this.http.post<EnquiryDraft>(this.url, data); }
  update(id: string, version: number | null, data: EnquiryDraftRequest): Observable<EnquiryDraft> {
    return this.http.put<EnquiryDraft>(`${this.url}/${id}`, data, { headers: { 'If-Match': ifMatch(version) } });
  }
  delete(id: string, version: number | null): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`, { headers: { 'If-Match': ifMatch(version) } });
  }
}

/** Local wall-clock value of a date picker as `YYYY-MM-DDTHH:mm:00`, without converting to the browser's zone. */
export function toLocalDateTime(date: Date | null): string | null {
  if (!date) return null;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`;
}

/** Inverse of {@link toLocalDateTime}: the wall-clock value shown in the picker. */
export function fromLocalDateTime(value: string | null): Date | null {
  if (!value) return null;
  const [date, time = '00:00'] = value.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  return new Date(y, m - 1, d, hh, mm);
}

/** Random key per form session; falls back where `crypto.randomUUID` is unavailable (non-secure origins). */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}
