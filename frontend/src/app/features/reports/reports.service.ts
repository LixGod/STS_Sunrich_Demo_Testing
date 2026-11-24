import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, toHttpParams } from '../../core/api/api.models';

export const DOCUMENT_KINDS = ['QUOTATION', 'JPO', 'MOORING_PLAN', 'SOF', 'FINAL_REPORT', 'INVOICE', 'CREDIT_NOTE', 'FDA'] as const;
export const DOCUMENT_STATUSES = ['DRAFT', 'PREPARED', 'SUBMITTED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'ISSUED', 'ACCEPTED',
  'SUPERSEDED', 'WITHDRAWN'] as const;
export const GROUPS = ['CLIENT', 'VESSEL', 'CARGO', 'MONTH'] as const;
export type GroupBy = (typeof GROUPS)[number];

/** One generated document revision from the register (FR-DOC-01). */
export interface RegisterRow {
  kind: string; id: string; number: string | null; revision: number; status: string; jobId: string | null; jobNumber: string | null;
  enquiryId: string | null; issuedAt: string | null; issuedBy: string | null; updatedAt: string; pdfSha256: string | null;
}
export interface RegisterFilters { kind: string | null; status: string | null; from: string | null; to: string | null; q: string | null }

export interface NamedOption { id: string; name: string }
export interface CargoTotal { grade: string; unit: string; quantity: number }
export interface HistoryJob {
  jobId: string; jobNumber: string; status: string; jobDate: string; client: string | null; motherVessel: string | null;
  daughterVessel: string | null; cargo: CargoTotal[];
}
export interface HistoryGroup { key: string; label: string; jobs: number; cargo: CargoTotal[] }
export interface JobHistory { groupBy: GroupBy; groups: HistoryGroup[]; jobs: Page<HistoryJob>; capped: boolean }
export interface HistoryOptions { clients: NamedOption[]; vessels: NamedOption[]; cargoGrades: NamedOption[] }
export interface HistoryFilters {
  from: string | null; to: string | null; clientId: string | null; vesselId: string | null; cargoGradeId: string | null;
  status: string | null;
}

/** The screen of the module that opens a document, from its kind. */
export function documentLink(row: RegisterRow): unknown[] | null {
  switch (row.kind) {
    case 'QUOTATION': return row.enquiryId ? ['/commercial/enquiries', row.enquiryId, 'quotation'] : null;
    case 'INVOICE': case 'CREDIT_NOTE': return ['/finance/invoices', row.id];
    default: {
      const page: Record<string, string> = { JPO: 'jpo', MOORING_PLAN: 'mooring-plan', SOF: 'operations', FINAL_REPORT: 'final-report', FDA: 'finance' };
      return row.jobId && page[row.kind] ? ['/jobs', row.jobId, page[row.kind]] : null;
    }
  }
}

@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/reports`;

  documents(filters: RegisterFilters, page: number, size: number): Observable<Page<RegisterRow>> {
    return this.http.get<Page<RegisterRow>>(`${this.base}/documents`, { params: toHttpParams({ ...filters, page, size }) });
  }

  jobHistory(filters: HistoryFilters, groupBy: GroupBy, page: number, size: number): Observable<JobHistory> {
    return this.http.get<JobHistory>(`${this.base}/job-history`, { params: toHttpParams({ ...filters, groupBy, page, size }) });
  }

  options(): Observable<HistoryOptions> {
    return this.http.get<HistoryOptions>(`${this.base}/job-history/options`);
  }

  jobHistoryXlsxUrl(filters: HistoryFilters, groupBy: GroupBy): string {
    return `${this.base}/job-history/xlsx?${toHttpParams({ ...filters, groupBy }).toString()}`;
  }
}
