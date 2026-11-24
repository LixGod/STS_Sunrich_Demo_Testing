import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import Big from 'big.js';
import { Observable } from 'rxjs';
import { API_BASE_URL, SILENT_ERRORS, ifMatch } from '../../../core/api/api.models';

export type LineKind = 'COST' | 'REVENUE';

export interface EstimateLine {
  lineNo: number;
  kind: LineKind;
  costHeadId: string;
  costHeadCode: string | null;
  costHeadName: string | null;
  description: string | null;
  quantity: number;
  unitPrice: number;
  amount: number;
  equipmentModelId: string | null;
  extraHours: boolean;
}

/** An equipment model offered on cost lines: its category cost head, optional standard rate and free units now. */
export interface EquipmentOption {
  modelId: string;
  name: string;
  categoryLabel: string;
  costHeadId: string | null;
  standardRate: number | null;
  standardRateCurrency: string | null;
  standardRateUsd?: number | null;
  available: number;
}

export function equipmentRate(option: EquipmentOption | undefined, currency: string | null): number | null {
  return option?.standardRate != null && option.standardRateCurrency === currency ? option.standardRate : null;
}

export interface Estimate {
  id: string;
  enquiryId: string;
  versionNo: number;
  currency: string;
  notes: string | null;
  tariffId: string | null;
  inrFxRate: number | null;
  lines: EstimateLine[];
  totalCost: number;
  totalRevenue: number;
  margin: number;
  marginPercent: number | null;
  locked: boolean;
  editable: boolean;
  updatedAt: string | null;
  version: number | null;
}

export interface EstimateSummary {
  id: string;
  versionNo: number;
  currency: string;
  inrFxRate: number | null;
  totalCost: number;
  totalRevenue: number;
  marginPercent: number | null;
  locked: boolean;
  updatedAt: string | null;
}

export interface EstimateLineRequest {
  kind: LineKind;
  costHeadId: string;
  description: string | null;
  /** Decimal strings, so no binary rounding happens on the way to the server. */
  quantity: string;
  unitPrice: string;
  equipmentModelId: string | null;
  extraHours: boolean;
}

export interface EstimateRequest {
  currency: string;
  notes: string | null;
  tariffId: string | null;
  inrFxRate: number | null;
  lines: EstimateLineRequest[];
}

export interface BranchCostDefault {
  id: string;
  branchId: string;
  costHeadId: string;
  description: string | null;
  quantity: number;
  unitPrice: number;
  active: boolean;
  equipmentModelId: string | null;
  version: number | null;
}

export type BranchCostDefaultRequest = Pick<BranchCostDefault, 'costHeadId' | 'description' | 'quantity' | 'unitPrice' | 'active' | 'equipmentModelId'>;

@Injectable({ providedIn: 'root' })
export class BranchCostDefaultService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private url(branchId: string) { return `${this.base}/commercial/branches/${branchId}/cost-defaults`; }
  list(branchId: string): Observable<BranchCostDefault[]> { return this.http.get<BranchCostDefault[]>(this.url(branchId)); }
  equipmentOptions(branchId: string): Observable<EquipmentOption[]> { return this.http.get<EquipmentOption[]>(`${this.url(branchId)}/equipment-options`); }
  create(branchId: string, request: BranchCostDefaultRequest): Observable<BranchCostDefault> {
    return this.http.post<BranchCostDefault>(this.url(branchId), request);
  }
  update(branchId: string, row: BranchCostDefault, request: BranchCostDefaultRequest): Observable<BranchCostDefault> {
    return this.http.put<BranchCostDefault>(`${this.url(branchId)}/${row.id}`, request, { headers: { 'If-Match': ifMatch(row.version) } });
  }
  delete(branchId: string, row: BranchCostDefault): Observable<void> {
    return this.http.delete<void>(`${this.url(branchId)}/${row.id}`, { headers: { 'If-Match': ifMatch(row.version) } });
  }
}

export interface Totals {
  cost: Big;
  revenue: Big;
  margin: Big;
  /** Null when there is no revenue. */
  marginPercent: Big | null;
}

/**
 * Live totals for the editor, computed exactly with big.js (JavaScript numbers would give rounding errors on money).
 * Mirrors the server: amount = quantity × unit price, half-up to 4 decimals; the server's totals remain authoritative.
 */
export function estimateTotals(lines: { kind: LineKind | null; quantity: number | null; unitPrice: number | null }[]): Totals {
  let cost = new Big(0);
  let revenue = new Big(0);
  for (const line of lines) {
    const amount = lineAmount(line.quantity, line.unitPrice);
    if (line.kind === 'COST') cost = cost.plus(amount);
    else if (line.kind === 'REVENUE') revenue = revenue.plus(amount);
  }
  const margin = revenue.minus(cost);
  const marginPercent = revenue.gt(0) ? margin.times(100).div(revenue).round(2, Big.roundHalfUp) : null;
  return { cost, revenue, margin, marginPercent };
}

export function lineAmount(quantity: number | null, unitPrice: number | null): Big {
  if (quantity === null || unitPrice === null) return new Big(0);
  return new Big(quantity).times(unitPrice).round(4, Big.roundHalfUp);
}

/** Hide the SPECIAL-REQ head unless the enquiry has special requirements or an existing line already uses it. */
export function filterEstimateCostHeads<T extends { value: string; meta?: Record<string, string | null> }>(
  heads: readonly T[],
  kind: LineKind,
  hasSpecialRequirement: boolean,
  usedHeadIds: ReadonlySet<string> = new Set(),
): T[] {
  return heads.filter((h) => {
    const headKind = h.meta?.['kind'];
    if (kind === 'COST' ? headKind === 'REVENUE' : headKind === 'EXPENSE') return false;
    if (h.meta?.['code'] === 'SPECIAL-REQ' && !hasSpecialRequirement && !usedHeadIds.has(h.value)) return false;
    return true;
  });
}

@Injectable({ providedIn: 'root' })
export class EstimateService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  list(enquiryId: string): Observable<EstimateSummary[]> {
    return this.http.get<EstimateSummary[]>(`${this.base}/enquiries/${enquiryId}/estimates`);
  }

  equipmentOptions(id: string): Observable<EquipmentOption[]> {
    return this.http.get<EquipmentOption[]>(`${this.base}/estimates/${id}/equipment-options`);
  }

  get(id: string): Observable<Estimate> {
    return this.http.get<Estimate>(`${this.base}/estimates/${id}`);
  }

  /** New version, copied from `copyFromId` (default: the latest version). */
  create(enquiryId: string, copyFromId: string | null, currency: string | null): Observable<Estimate> {
    return this.http.post<Estimate>(`${this.base}/enquiries/${enquiryId}/estimates`, { copyFromId, currency }, { context: this.silent });
  }

  update(id: string, version: number | null, request: EstimateRequest): Observable<Estimate> {
    return this.http.put<Estimate>(`${this.base}/estimates/${id}`, request, { headers: { 'If-Match': ifMatch(version) }, context: this.silent });
  }
}
