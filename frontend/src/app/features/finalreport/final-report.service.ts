import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../core/api/api.models';

export type ReportStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'ISSUED' | 'SUPERSEDED';
export type ReportAction = 'EDIT' | 'SUBMIT' | 'APPROVE' | 'RETURN' | 'ISSUE';

export interface EquipmentLine { category: 'FENDER' | 'HOSE' | 'OTHER'; description: string; quantity: number | null }
export interface AttendanceLine { capacity: string; name: string }
export interface ReportContent {
  vhfChannel: string | null; summary: string | null; remarks: string | null; equipment: EquipmentLine[]; attendance: AttendanceLine[];
  attachmentDocumentIds: string[];
}

export interface ReportSnapshot {
  jobNumber: string; client: string | null; timezone: string; operationType: string | null;
  vessels: { role: string; name: string | null; imo: string | null; type: string | null }[];
  cargo: { gradeName: string | null; gradeCode: string | null; quantity: string | number; unit: string | null }[];
  q88: { vessel: string | null; imo: string | null; documentName: string; uploadedAt: string }[];
  events: { phase: string; label: string; vessel: string | null; occurredAt: string | null; startedAt: string | null; endedAt: string | null;
    remarks: string | null }[];
  grades: { gradeName: string | null; gradeCode: string | null; unit: string | null; planned: string | number; finalQuantity: string | number | null;
    completed: boolean }[];
  delays: { category: string; startedAt: string; endedAt: string | null; description: string }[];
  incidents: { category: string; severity: string; occurredAt: string; description: string; status: string }[];
  sof: { revisionNo: number; issuedAt: string | null; pdfSha256: string | null } | null;
  attachments: { id: string; name: string; type: string | null }[];
  takenAt: string;
}

export interface ReportIssue { code: string; message: string }

export interface ReportRevision {
  id: string; revisionNo: number; reference: string; documentId: string; templateVersion: number; status: ReportStatus; content: ReportContent;
  snapshot: ReportSnapshot; snapshotFrozen: boolean; blockers: ReportIssue[]; warnings: ReportIssue[]; sources: Record<string, string>;
  submittedByName: string | null; submittedAt: string | null; decidedByName: string | null; decidedAt: string | null; returnedReason: string | null;
  issuedByName: string | null; issuedAt: string | null; pdfSha256: string | null; allowedActions: ReportAction[]; updatedAt: string | null;
  version: number;
}

export interface ReportOverview {
  jobId: string; jobNumber: string; revisions: { id: string; revisionNo: number; status: ReportStatus; issuedAt: string | null }[]; canCreate: boolean;
}

export interface JobDocumentRef { id: string; fileName: string }

@Injectable({ providedIn: 'root' })
export class FinalReportService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private path(jobId: string) { return `${this.base}/jobs/${jobId}/final-report`; }
  private headers(r: ReportRevision) { return { headers: { 'If-Match': ifMatch(r.version) } }; }

  overview(jobId: string): Observable<ReportOverview> { return this.http.get<ReportOverview>(this.path(jobId)); }
  revision(jobId: string, id: string): Observable<ReportRevision> { return this.http.get<ReportRevision>(`${this.path(jobId)}/revisions/${id}`); }
  create(jobId: string): Observable<ReportRevision> { return this.http.post<ReportRevision>(`${this.path(jobId)}/revisions`, null); }
  save(jobId: string, r: ReportRevision, content: ReportContent): Observable<ReportRevision> {
    return this.http.put<ReportRevision>(`${this.path(jobId)}/revisions/${r.id}`, content, this.headers(r));
  }
  action(jobId: string, r: ReportRevision, action: 'submit' | 'issue' | 'regenerate-summary'): Observable<ReportRevision> {
    return this.http.post<ReportRevision>(`${this.path(jobId)}/revisions/${r.id}/${action}`, null, this.headers(r));
  }
  decide(jobId: string, r: ReportRevision, decision: 'APPROVE' | 'RETURN', reason: string | null): Observable<ReportRevision> {
    return this.http.post<ReportRevision>(`${this.path(jobId)}/revisions/${r.id}/decision`, { decision, reason }, this.headers(r));
  }
  pdfUrl(jobId: string, r: ReportRevision): string { return `${this.path(jobId)}/revisions/${r.id}/pdf`; }
  /** Job documents that can be listed as attachments (read through the technical collection endpoint). */
  documents(jobId: string): Observable<JobDocumentRef[]> {
    return this.http.get<JobDocumentRef[]>(`${this.base}/jobs/${jobId}/technical/documents`);
  }
}
