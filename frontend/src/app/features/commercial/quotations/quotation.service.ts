import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, SILENT_ERRORS, ifMatch } from '../../../core/api/api.models';
import { EnquiryStatus } from '../enquiries/enquiry.service';

export type QuotationStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'ISSUED' | 'SUPERSEDED' | 'ACCEPTED' | 'OWNER_REJECTED';
export type QuotationAction = 'EDIT' | 'SUBMIT' | 'APPROVE' | 'REJECT' | 'ISSUE' | 'ACCEPT' | 'OWNER_REJECT' | 'RECORD_ADVANCE';
export type AdvancePaymentStatus = 'UNPAID' | 'PARTIAL' | 'PAID';
export type IssueChannel = 'EMAIL' | 'OTHER';

export interface AdvancePayment {
  id: string;
  amount: number;
  note: string | null;
  recordedByName: string | null;
  recordedAt: string;
}

export interface TariffTerms {
  id: string;
  code: string;
  name: string;
  currency: string;
  baseHours: number;
  baseCharge: number;
  extraHourRate: number;
  billingIncrementMinutes: number | null;
  graceMinutes: number | null;
  roundingRule: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  termsPending: boolean;
}

export interface QuotationLine {
  lineNo: number;
  costHeadCode: string;
  costHeadName: string;
  description: string | null;
  quantity: number;
  unitPrice: number;
  amount: number;
}

export interface Acceptance {
  acceptedAt: string;
  acceptedByName: string;
  workOrderReference: string | null;
  paymentTerms: string | null;
  specialConditions: string | null;
  afterValidity: boolean;
  recordedByName: string | null;
  recordedAt: string;
  jobId: string;
  jobNumber: string | null;
}

export interface QuotationVersion {
  id: string;
  versionNo: number;
  status: QuotationStatus;
  estimateId: string;
  estimateVersionNo: number | null;
  currency: string;
  inrFxRate: number | null;
  totalAmount: number;
  finalAmount: number;
  advancePaid: number;
  advanceRemaining: number;
  advanceStatus: AdvancePaymentStatus;
  advancePayments: AdvancePayment[];
  validUntil: string;
  paymentTerms: string | null;
  advanceReceived: number | null;
  advanceGiven: number | null;
  termsText: string | null;
  scopeOfServices: string | null;
  tariff: TariffTerms | null;
  lines: QuotationLine[];
  submittedByName: string | null;
  submittedAt: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  issuedByName: string | null;
  issuedAt: string | null;
  issuedTo: string | null;
  issueChannel: IssueChannel | null;
  issueNote: string | null;
  pdfSha256: string | null;
  acceptance: Acceptance | null;
  allowedActions: QuotationAction[];
  updatedAt: string | null;
  version: number | null;
}

export interface QuotationOverview {
  enquiryId: string;
  enquiryReference: string;
  enquiryStatus: EnquiryStatus;
  quotationId: string | null;
  reference: string | null;
  /** Newest first. */
  versions: QuotationVersion[];
  canCreateVersion: boolean;
  job: { id: string; jobNumber: string } | null;
}

export interface PendingApproval {
  versionId: string;
  enquiryId: string;
  enquiryReference: string;
  quotationReference: string;
  versionNo: number;
  clientName: string | null;
  currency: string;
  totalAmount: number;
  submittedByName: string | null;
  submittedAt: string | null;
}

export interface QuotationTerms {
  /** `YYYY-MM-DD`. */
  validUntil: string;
  paymentTerms: string | null;
  advanceReceived: number | null;
  advanceGiven: number | null;
  termsText: string | null;
  scopeOfServices: string | null;
  tariffId: string | null;
  inrFxRate: number | null;
}

export interface AcceptanceRequest {
  acceptedAt: string;
  acceptedByName: string;
  jobNumber: string | null;
  paymentTerms: string | null;
  specialConditions: string | null;
}

/** shortcut: weekends are excluded; add a holiday calendar when business holidays are defined. */
export function addWorkingDays(start: Date, days: number): string {
  const day = new Date(start.getFullYear(), start.getMonth(), start.getDate());
  for (let left = days; left > 0;) {
    day.setDate(day.getDate() + 1);
    if (day.getDay() !== 0 && day.getDay() !== 6) left--;
  }
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}

@Injectable({ providedIn: 'root' })
export class QuotationService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  overview(enquiryId: string): Observable<QuotationOverview> {
    return this.http.get<QuotationOverview>(`${this.base}/enquiries/${enquiryId}/quotation`);
  }

  pendingApproval(): Observable<PendingApproval[]> {
    return this.http.get<PendingApproval[]>(`${this.base}/quotation-versions/pending-approval`, { context: this.silent });
  }

  createVersion(enquiryId: string, estimateId: string, terms: QuotationTerms): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/enquiries/${enquiryId}/quotation/versions`, { estimateId, ...terms }, { context: this.silent });
  }

  update(version: QuotationVersion, terms: QuotationTerms): Observable<QuotationOverview> {
    return this.http.put<QuotationOverview>(`${this.base}/quotation-versions/${version.id}`, terms, this.options(version));
  }

  submit(version: QuotationVersion): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/submit`, null, this.options(version));
  }

  decide(version: QuotationVersion, approve: boolean, reason: string | null): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/decision`, { approve, reason }, this.options(version));
  }

  issue(version: QuotationVersion, issuedTo: string, channel: IssueChannel, note: string | null): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/issue`, { issuedTo, channel, note }, this.options(version));
  }

  /** The key makes a retried or double-clicked acceptance return the first result (one job only). */
  accept(version: QuotationVersion, request: AcceptanceRequest, idempotencyKey: string): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/acceptance`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
      context: this.silent,
    });
  }

  rejectByOwner(version: QuotationVersion): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/owner-rejection`, null, this.options(version));
  }

  recordAdvance(version: QuotationVersion, amount: number, note: string | null): Observable<QuotationOverview> {
    return this.http.post<QuotationOverview>(`${this.base}/quotation-versions/${version.id}/advances`, { amount, note }, this.options(version));
  }

  /** Opened in a new tab; the browser's own viewer shows it (issued: the frozen PDF; before: a DRAFT rendering). */
  pdfUrl(version: QuotationVersion): string {
    return `${this.base}/quotation-versions/${version.id}/pdf`;
  }

  previewPages(version: QuotationVersion): Observable<{ pages: number }> {
    return this.http.get<{ pages: number }>(`${this.base}/quotation-versions/${version.id}/pdf/pages`, { context: this.silent });
  }

  previewPageUrl(version: QuotationVersion, page: number): string {
    const tag = encodeURIComponent(version.pdfSha256 ?? String(version.version ?? 0));
    return `${this.base}/quotation-versions/${version.id}/pdf/preview/${page}?v=${tag}`;
  }

  private options(version: QuotationVersion) {
    return { headers: { 'If-Match': ifMatch(version.version) }, context: this.silent };
  }
}
