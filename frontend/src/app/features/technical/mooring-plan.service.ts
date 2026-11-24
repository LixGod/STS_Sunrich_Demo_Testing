import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../core/api/api.models';

export type MooringStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'ISSUED' | 'SUPERSEDED';
export type MooringAction = 'EDIT' | 'VERIFY_VALUE' | 'SUBMIT' | 'APPROVE' | 'RETURN' | 'ISSUE';
export interface MooringVessel { jobVesselId: string; heading: 'CONSTANT' | 'MANOEUVRING' | null; lateralOffsetM: number | null; orientation: 'BOW_UP' | 'BOW_DOWN' }
export interface MooringLayer { id: string; jobVesselId: string; documentVersionId: string; xM: number; yM: number; scale: number; visible: boolean }
export interface MooringObject { id: string; type: string; xM: number; yM: number; jobVesselId: string | null; label: string | null }
export interface MooringCapacity { columnCode: string; value: number | null; unit: string | null; sourceDocumentId: string | null; sourcePage: number | null; sourceText: string | null }
export interface MooringLine { id: string; label: string; jobVesselId: string; fromPointId: string | null; toPointId: string | null; berthingOrder: number | null; unmooringOrder: number | null; capacities: MooringCapacity[] }
export interface MooringParticular { jobVesselId: string; code: string; value: number | null; unit: 'm'; sourceDocumentId: string | null; sourcePage: number | null; sourceText: string | null }
export interface MooringModel {
  schemaVersion: 1; vessels: MooringVessel[]; layers: MooringLayer[]; objects: MooringObject[];
  lines: MooringLine[]; particulars: MooringParticular[]; rulerStepM: number | null;
}
export interface MooringDimension { jobVesselId: string; code: string; value: number | null; unit: string; particularsVersionId: string | null; sourceDocumentId: string | null; sourcePage: number | null; verified: boolean }
export interface MooringVesselFacts { jobVesselId: string; role: string; name: string | null; imo: string | null; dimensions: MooringDimension[] }
export interface MooringColumn { code: string; label: string; definition: string | null; unit: string | null; evidenceRequirement: string | null; status: string; version: number }
export interface MooringRule { toleranceM: number | null; active: boolean; version: number }
export interface MooringSnapshot { vessels: MooringVesselFacts[]; capacityColumns: MooringColumn[]; verifiedValues: { key: string; verifiedBy: string; verifiedAt: string }[]; takenAt: string }
export interface MooringRevision {
  id: string; revisionNo: number; status: MooringStatus; model: MooringModel; snapshot: MooringSnapshot; snapshotFrozen: boolean;
  blockers: { code: string; message: string }[]; warnings: string[];
  submittedByName: string | null; submittedAt: string | null; decidedByName: string | null; decidedAt: string | null;
  returnedReason: string | null; issuedByName: string | null; issuedAt: string | null; pdfSha256: string | null;
  allowedActions: MooringAction[]; version: number;
}
export interface MooringOverview { jobId: string; jobNumber: string; revisions: { id: string; revisionNo: number; status: MooringStatus; issuedAt: string | null }[]; canCreate: boolean }

@Injectable({ providedIn: 'root' })
export class MooringPlanService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private path(jobId: string) { return `${this.base}/jobs/${jobId}/mooring-plan`; }
  overview(jobId: string): Observable<MooringOverview> { return this.http.get<MooringOverview>(this.path(jobId)); }
  revision(jobId: string, id: string): Observable<MooringRevision> { return this.http.get<MooringRevision>(`${this.path(jobId)}/revisions/${id}`); }
  create(jobId: string): Observable<MooringRevision> { return this.http.post<MooringRevision>(`${this.path(jobId)}/revisions`, null); }
  save(jobId: string, revision: MooringRevision, model: MooringModel): Observable<MooringRevision> {
    return this.http.put<MooringRevision>(`${this.path(jobId)}/revisions/${revision.id}`, model,
      { headers: { 'If-Match': ifMatch(revision.version) } });
  }
  verify(jobId: string, revision: MooringRevision, key: string): Observable<MooringRevision> {
    return this.action(jobId, revision, 'verify-value', { key });
  }
  submit(jobId: string, revision: MooringRevision): Observable<MooringRevision> { return this.action(jobId, revision, 'submit'); }
  decide(jobId: string, revision: MooringRevision, decision: 'APPROVE' | 'RETURN', reason: string | null): Observable<MooringRevision> {
    return this.action(jobId, revision, 'decision', { decision, reason });
  }
  issue(jobId: string, revision: MooringRevision): Observable<MooringRevision> { return this.action(jobId, revision, 'issue'); }
  pdfUrl(jobId: string, revision: MooringRevision): string { return `${this.path(jobId)}/revisions/${revision.id}/pdf`; }
  columns(): Observable<MooringColumn[]> { return this.http.get<MooringColumn[]>(`${this.base}/technical/mooring-capacity-columns`); }
  defineColumn(column: MooringColumn, definition: string, unit: string, evidenceRequirement: string): Observable<MooringColumn> {
    return this.http.put<MooringColumn>(`${this.base}/technical/mooring-capacity-columns/${column.code}`,
      { definition, unit, evidenceRequirement }, { headers: { 'If-Match': ifMatch(column.version) } });
  }
  rule(): Observable<MooringRule> { return this.http.get<MooringRule>(`${this.base}/technical/mooring-rule`); }
  updateRule(rule: MooringRule, toleranceM: number | null, active: boolean): Observable<MooringRule> {
    return this.http.put<MooringRule>(`${this.base}/technical/mooring-rule`, { toleranceM, active },
      { headers: { 'If-Match': ifMatch(rule.version) } });
  }
  private action(jobId: string, revision: MooringRevision, action: string, body?: object): Observable<MooringRevision> {
    return this.http.post<MooringRevision>(`${this.path(jobId)}/revisions/${revision.id}/${action}`, body ?? null,
      { headers: { 'If-Match': ifMatch(revision.version) } });
  }
}
