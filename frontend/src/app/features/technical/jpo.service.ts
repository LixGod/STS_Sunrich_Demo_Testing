import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../core/api/api.models';

export type JpoStatus = 'DRAFT' | 'DONE' | 'SUBMITTED' | 'APPROVED' | 'ISSUED' | 'SUPERSEDED';
export type JpoAction = 'EDIT' | 'SUBMIT' | 'APPROVE' | 'RETURN' | 'ISSUE' | 'ACCEPT' | 'NEW_REVISION';
export type JpoParty = 'DISCHARGING_MASTER' | 'RECEIVING_MASTER' | 'STS_SUPERINTENDENT';

export interface OperatingLimit { parameter: string; limit: string; unit: string | null; action: string }
export interface CargoRate { cargoGradeCode: string; maxRatePerLine: string | null; rateUnit: string | null }
export interface EquipmentRow { category: string; number: number | null; dimensions: string | null; strings: number | null }
export interface AncillaryRow { item: string; value: string | null }

/** Everything entered on the JPO (job facts are in the snapshot). Choice fields hold fixed codes. */
export interface JpoContent {
  operationDate: string | null;
  purpose: string | null;
  reverseLightering: boolean;
  similarLoaBerthing: boolean;
  mooringDiagramDocumentId: string | null;
  mooringNotes: string | null;
  constantHeadingCourseSpeed: string | null;
  manoeuvringCourseSpeed: string | null;
  shipHandling: string | null;
  rudderType: string | null;
  maxRudderAngleDeg: string | null;
  handedness: string | null;
  constantHeadingPropulsion: string | null;
  manoeuvringPropulsion: string | null;
  constantHeadingDpMode: string | null;
  manoeuvringDpMode: string | null;
  constantHeadingDeadSlow: string | null;
  manoeuvringDeadSlowKn: string | null;
  manoeuvringDeadSlowCondition: string | null;
  speedMonitoring: string | null;
  rendezvous: string | null;
  transferLocation: string | null;
  approach: string | null;
  approachNotes: string | null;
  supportCraft: string | null;
  additionalRequirements: string | null;
  mooringRopeFailure: string | null;
  operatingLimits: OperatingLimit[];
  securityLevel: string | null;
  contingency: string | null;
  personnelTransfer: string | null;
  cargoRates: CargoRate[];
  oilBoom: string | null;
  unmooringDiagramDocumentId: string | null;
  unmooringNotes: string | null;
  equipment: EquipmentRow[];
  ancillary: AncillaryRow[];
}

export interface JpoSnapshot {
  jobNumber: string;
  operationType: string | null;
  vessels: { jobVesselId: string; role: string; name: string | null; imo: string | null; etaUtc: string | null; etaTimezone: string | null }[];
  cargo: { gradeCode: string | null; gradeName: string | null; quantity: string; unitCode: string | null }[];
  voyage: { role: string; fieldCode: string; label: string; unit: string; value: string; sourceDocumentId: string | null; sourcePage: number | null }[];
  voyageVersionIds: Record<string, string | null>;
  checklists: { role: string | null; code: string; name: string; status: string }[];
  warnings: string[];
  takenAt: string;
  /** The job's approved operating limits (3e); null while none are approved. */
  operatingLimits?: { parameterCode: string; parameterLabel: string; unit: string; operation: string; comparison: 'ABOVE' | 'BELOW';
    threshold: string | number; action: string }[] | null;
  operatingLimitsRevision?: number | null;
  allocatedResources?: { kind: string; category: string | null; identifier: string; dimensions: string | null }[];
}

export interface JpoAcceptance {
  party: JpoParty; personName: string; acceptedAt: string; signedDocumentId: string | null; recordedByName: string | null; recordedAt: string;
}

export interface AnnexSection { code: string; title: string; body: string; appliesWhen: string }

export interface JpoRevision {
  id: string;
  revisionNo: number;
  status: JpoStatus;
  content: JpoContent;
  snapshot: JpoSnapshot;
  snapshotFrozen: boolean;
  sources: Record<string, 'MANUAL' | 'LINKED'>;
  blockers: { code: string; message: string }[];
  warnings: string[];
  annexTemplateVersion: number | null;
  annexSections: AnnexSection[];
  submittedByName: string | null;
  submittedAt: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  returnedReason: string | null;
  issuedByName: string | null;
  issuedAt: string | null;
  pdfSha256: string | null;
  acceptances: JpoAcceptance[];
  allowedActions: JpoAction[];
  updatedAt: string | null;
  version: number;
}

export interface JpoOverview {
  jobId: string;
  jobNumber: string;
  revisions: { id: string; revisionNo: number; status: JpoStatus; issuedAt: string | null; acceptanceCount: number }[];
  canCreate: boolean;
}

export interface AnnexTemplate {
  id: string; versionNo: number; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'; name: string; sections: AnnexSection[];
  ownerConfirmation: string | null; publishedByName: string | null; publishedAt: string | null; updatedByName: string | null;
  updatedAt: string | null; version: number;
}

@Injectable({ providedIn: 'root' })
export class JpoService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private path(jobId: string) { return `${this.base}/jobs/${jobId}/jpo`; }

  overview(jobId: string): Observable<JpoOverview> { return this.http.get<JpoOverview>(this.path(jobId)); }
  revision(jobId: string, id: string): Observable<JpoRevision> { return this.http.get<JpoRevision>(`${this.path(jobId)}/revisions/${id}`); }
  create(jobId: string): Observable<JpoRevision> { return this.http.post<JpoRevision>(`${this.path(jobId)}/revisions`, null); }
  save(jobId: string, revision: JpoRevision, content: JpoContent): Observable<JpoRevision> {
    return this.http.put<JpoRevision>(`${this.path(jobId)}/revisions/${revision.id}`, content, { headers: { 'If-Match': ifMatch(revision.version) } });
  }
  submit(jobId: string, revision: JpoRevision): Observable<JpoRevision> { return this.action(jobId, revision, 'submit'); }
  decide(jobId: string, revision: JpoRevision, decision: 'APPROVE' | 'RETURN', reason: string | null): Observable<JpoRevision> {
    return this.action(jobId, revision, 'decision', { decision, reason });
  }
  issue(jobId: string, revision: JpoRevision): Observable<JpoRevision> { return this.action(jobId, revision, 'issue'); }
  accept(jobId: string, revision: JpoRevision, input: { party: JpoParty; personName: string; acceptedAt: string; signedDocumentId: string | null }):
    Observable<JpoRevision> {
    return this.http.post<JpoRevision>(`${this.path(jobId)}/revisions/${revision.id}/acceptances`, input);
  }
  pdfUrl(jobId: string, revision: JpoRevision): string { return `${this.path(jobId)}/revisions/${revision.id}/pdf`; }
  xlsxUrl(jobId: string, revision: JpoRevision): string { return `${this.path(jobId)}/revisions/${revision.id}/xlsx`; }

  annexTemplates(): Observable<AnnexTemplate[]> { return this.http.get<AnnexTemplate[]>(`${this.base}/technical/annex-templates`); }
  newAnnexDraft(): Observable<AnnexTemplate> { return this.http.post<AnnexTemplate>(`${this.base}/technical/annex-templates`, null); }
  saveAnnex(template: AnnexTemplate, input: { name: string; sections: AnnexSection[] }): Observable<AnnexTemplate> {
    return this.http.put<AnnexTemplate>(`${this.base}/technical/annex-templates/${template.id}`, input,
      { headers: { 'If-Match': ifMatch(template.version) } });
  }
  publishAnnex(template: AnnexTemplate, ownerConfirmation: string): Observable<AnnexTemplate> {
    return this.http.post<AnnexTemplate>(`${this.base}/technical/annex-templates/${template.id}/publish`, { ownerConfirmation },
      { headers: { 'If-Match': ifMatch(template.version) } });
  }

  private action(jobId: string, revision: JpoRevision, action: string, body?: object): Observable<JpoRevision> {
    return this.http.post<JpoRevision>(`${this.path(jobId)}/revisions/${revision.id}/${action}`, body ?? null,
      { headers: { 'If-Match': ifMatch(revision.version) } });
  }
}
