import { HttpClient, HttpEvent } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../core/api/api.models';

export interface JobTechnicalVessel { id: string; role: 'MOTHER' | 'DAUGHTER'; vesselId: string; name: string | null }
export interface JobTechnicalJob { id: string; jobNumber: string; vessels: JobTechnicalVessel[] }
export interface RequestItem {
  id: string; jobVesselId: string | null; code: string; label: string;
  kind: 'DOCUMENT' | 'DECLARATION' | 'CONTACT' | 'VOYAGE_DATA' | 'CHECKLIST';
  documentTypeId: string | null; required: boolean; status: string;
  responseText: string | null; reviewComment: string | null; documentVersionIds: string[]; version: number;
  events: { from: string | null; to: string; reason: string | null; actorId: string; occurredAt: string }[];
  phase?: 'PRE_ARRIVAL' | 'ON_ARRIVAL' | 'POST_DEPARTURE';
}
export interface JobRequest {
  id: string | null; jobId: string; status: string; templateVersion: number | null;
  items: RequestItem[]; version: number | null;
}
export interface JobDocument {
  id: string; jobVesselId: string | null; documentTypeId: string; versionNo: number;
  fileId: string; fileName: string; contentType: string; scanState: string; uploadedAt: string;
}
export interface EmailPreview { subject: string; body: string; unresolved: string[]; templateVersion: number }
export interface VoyageField { code: string; label: string; unit: string; requiredForReview: boolean }
export interface VoyageValue { fieldCode: string; value: string; sourceDocumentId: string | null; sourcePage: number | null; verified: boolean }
export interface VoyageVersion {
  id: string; jobVesselId: string; versionNo: number; status: string; notes: string | null;
  values: VoyageValue[]; submittedBy: string | null; submittedAt: string | null;
  verifiedBy: string | null; verifiedAt: string | null; returnedReason: string | null; version: number;
}
export interface VoyageOverview { jobVesselId: string; versions: VoyageVersion[] }
export interface CompatibilityReview {
  jobId: string; warnings: { code: string; vesselRole: string | null; message: string }[];
  fixedParticularsVersionIds: Record<string, string | null>; voyageVersionIds: Record<string, string | null>;
}
export interface ChecklistTemplate {
  id: string; code: string; name: string; versionNo: number; status: string; appliesTo: string;
  items: { code: string; label: string; answerType: string; required: boolean }[]; version: number;
}
export interface ChecklistResponse {
  id: string; jobId: string; jobVesselId: string | null; template: ChecklistTemplate;
  status: string; answers: Record<string, string | null>; signedDocumentId: string | null; version: number;
}
export interface RequestTemplate {
  id: string; versionNo: number; status: string; name: string; emailSubject: string; emailBody: string;
  items: { code: string; label: string; kind: string; appliesTo: string; documentTypeId: string | null; required: boolean }[];
  version: number;
}

@Injectable({ providedIn: 'root' })
export class JobTechnicalService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private path(jobId: string) { return `${this.base}/jobs/${jobId}/technical`; }

  job(jobId: string): Observable<JobTechnicalJob> { return this.http.get<JobTechnicalJob>(`${this.base}/jobs/${jobId}`); }
  request(jobId: string): Observable<JobRequest> { return this.http.get<JobRequest>(`${this.path(jobId)}/request`); }
  initialise(jobId: string): Observable<JobRequest> {
    return this.http.post<JobRequest>(`${this.path(jobId)}/request`, null, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  }
  changeItem(jobId: string, item: RequestItem, status: string, responseText: string | null, reason: string | null): Observable<JobRequest> {
    return this.http.patch<JobRequest>(`${this.path(jobId)}/request/items/${item.id}`, { status, responseText, reason },
      { headers: { 'If-Match': ifMatch(item.version) } });
  }
  addItem(jobId: string, input: { code: string; label: string; kind: string; documentTypeId: string | null;
    jobVesselId: string | null; required: boolean; reason: string; phase?: string }): Observable<JobRequest> {
    return this.http.post<JobRequest>(`${this.path(jobId)}/request/items`, input);
  }
  email(jobId: string): Observable<EmailPreview> { return this.http.get<EmailPreview>(`${this.path(jobId)}/request/email-preview`); }
  documents(jobId: string): Observable<JobDocument[]> { return this.http.get<JobDocument[]>(`${this.path(jobId)}/documents`); }
  upload(jobId: string, item: RequestItem, file: File): Observable<HttpEvent<JobDocument>> {
    const form = new FormData();
    if (item.jobVesselId) form.append('jobVesselId', item.jobVesselId);
    form.append('itemId', item.id);
    if (item.documentTypeId) form.append('documentTypeId', item.documentTypeId);
    form.append('extractVoyage', 'true');
    form.append('file', file, file.name);
    return this.http.post<JobDocument>(`${this.path(jobId)}/documents`, form, { observe: 'events', reportProgress: true });
  }
  voyageFields(jobId: string): Observable<VoyageField[]> { return this.http.get<VoyageField[]>(`${this.path(jobId)}/voyage-fields`); }
  voyage(jobId: string, vesselId: string): Observable<VoyageOverview> {
    return this.http.get<VoyageOverview>(`${this.path(jobId)}/vessels/${vesselId}/voyage`);
  }
  populateVoyageFromDoc(jobId: string, vesselId: string): Observable<VoyageVersion> {
    return this.http.post<VoyageVersion>(`${this.path(jobId)}/vessels/${vesselId}/voyage/from-document`, null);
  }
  saveVoyageFromDoc(jobId: string, vesselId: string,
    values: { fieldCode: string; value: string; sourceDocumentId: string | null; sourcePage: number | null }[]): Observable<VoyageVersion> {
    return this.http.put<VoyageVersion>(`${this.path(jobId)}/vessels/${vesselId}/voyage/from-document`, { notes: null, values });
  }
  newVoyage(jobId: string, vesselId: string): Observable<VoyageVersion> {
    return this.http.post<VoyageVersion>(`${this.path(jobId)}/vessels/${vesselId}/voyage`, null);
  }
  saveVoyage(jobId: string, version: VoyageVersion, notes: string | null,
    values: { fieldCode: string; value: string; sourceDocumentId: string | null; sourcePage: number | null }[]): Observable<VoyageVersion> {
    return this.http.put<VoyageVersion>(`${this.path(jobId)}/voyage/${version.id}`, { notes, values },
      { headers: { 'If-Match': ifMatch(version.version) } });
  }
  voyageAction(jobId: string, version: VoyageVersion, action: 'submit' | 'verify' | 'return', body?: object): Observable<VoyageVersion> {
    return this.http.post<VoyageVersion>(`${this.path(jobId)}/voyage/${version.id}/${action}`, body ?? null,
      { headers: { 'If-Match': ifMatch(version.version) } });
  }
  review(jobId: string): Observable<CompatibilityReview> { return this.http.get<CompatibilityReview>(`${this.path(jobId)}/review`); }
  templates(): Observable<ChecklistTemplate[]> { return this.http.get<ChecklistTemplate[]>(`${this.base}/technical/checklist-templates`); }
  requestTemplates(): Observable<RequestTemplate[]> { return this.http.get<RequestTemplate[]>(`${this.base}/technical/request-templates`); }
  saveRequestTemplate(input: Omit<RequestTemplate, 'id' | 'versionNo' | 'status' | 'version'>, current?: RequestTemplate): Observable<RequestTemplate> {
    if (current) return this.http.put<RequestTemplate>(`${this.base}/technical/request-templates/${current.id}`, input,
      { headers: { 'If-Match': ifMatch(current.version) } });
    return this.http.post<RequestTemplate>(`${this.base}/technical/request-templates`, input);
  }
  publishRequestTemplate(current: RequestTemplate, ownerConfirmation: string): Observable<RequestTemplate> {
    return this.http.post<RequestTemplate>(`${this.base}/technical/request-templates/${current.id}/publish`,
      { ownerConfirmation }, { headers: { 'If-Match': ifMatch(current.version) } });
  }
  saveChecklistTemplate(input: { code: string; name: string; appliesTo: string;
    items: { code: string; label: string; answerType: string; required: boolean }[] }, current?: ChecklistTemplate): Observable<ChecklistTemplate> {
    if (current) return this.http.put<ChecklistTemplate>(`${this.base}/technical/checklist-templates/${current.id}`, input,
      { headers: { 'If-Match': ifMatch(current.version) } });
    return this.http.post<ChecklistTemplate>(`${this.base}/technical/checklist-templates`, input);
  }
  publishChecklistTemplate(current: ChecklistTemplate): Observable<ChecklistTemplate> {
    return this.http.post<ChecklistTemplate>(`${this.base}/technical/checklist-templates/${current.id}/publish`, null,
      { headers: { 'If-Match': ifMatch(current.version) } });
  }
  checklists(jobId: string): Observable<ChecklistResponse[]> { return this.http.get<ChecklistResponse[]>(`${this.path(jobId)}/checklists`); }
  newChecklist(jobId: string, templateId: string, jobVesselId: string | null): Observable<ChecklistResponse> {
    return this.http.post<ChecklistResponse>(`${this.path(jobId)}/checklists`, { templateId, jobVesselId });
  }
  saveChecklist(jobId: string, response: ChecklistResponse, answers: { itemCode: string; answer: string | null }[],
    signedDocumentId: string | null, status: string): Observable<ChecklistResponse> {
    return this.http.put<ChecklistResponse>(`${this.path(jobId)}/checklists/${response.id}`,
      { answers, signedDocumentId, status }, { headers: { 'If-Match': ifMatch(response.version) } });
  }
}
