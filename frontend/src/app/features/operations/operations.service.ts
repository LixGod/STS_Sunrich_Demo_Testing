import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, ifMatch, toHttpParams } from '../../core/api/api.models';

export interface ListItem { code: string; label: string; sortOrder: number; active: boolean; version: number; phase?: string | null; interval?: boolean | null }

export interface EtaRevision {
  revisionNo: number; etaUtc: string; timezone: string; source: string; note: string | null; recordedByName: string | null;
  recordedAt: string | null; changeHours: string | number | null;
}
export interface VesselEta { jobVesselId: string; role: 'MOTHER' | 'DAUGHTER'; name: string | null; current: EtaRevision | null; history: EtaRevision[] }

export interface OperationEvent {
  id: string; typeCode: string; typeLabel: string; phase: string; interval: boolean; jobVesselId: string | null;
  occurredAt: string | null; startedAt: string | null; endedAt: string | null; remarks: string | null; source: string;
  status: 'ACTIVE' | 'VOIDED'; voidReason: string | null; recordedByName: string | null; recordedAt: string; corrected: boolean; version: number;
  clientEventId?: string | null; deviceRecordedAt?: string | null;
}
export interface EventInput {
  typeCode: string; jobVesselId: string | null; occurredAt: string | null; startedAt: string | null; endedAt: string | null;
  remarks: string | null; source: string; reason?: string | null;
  clientEventId?: string; deviceRecordedAt?: string; conflictReason?: string | null;
}
export interface Change { versionNo: number; before: Record<string, unknown>; reason: string; changedByName: string | null; changedAt: string }

export interface SofRevision {
  id: string; revisionNo: number; status: 'PREPARED' | 'ISSUED' | 'SUPERSEDED' | 'WITHDRAWN'; eventCount: number; preparedByName: string | null;
  preparedAt: string; issuedByName: string | null; issuedAt: string | null; pdfSha256: string | null; canIssue: boolean; canWithdraw: boolean;
  version: number;
}

export interface CargoReading {
  id: string; readAt: string; cumulative: string | number; kind: 'NORMAL' | 'FINAL' | 'CORRECTION'; reason: string | null;
  certifiedDocumentId: string | null; remarks: string | null; recordedByName: string | null; recordedAt: string;
  quantitySincePrevious: string | number | null; ratePerHour: string | number | null;
}
export interface CargoGrade {
  lineNo: number; gradeCode: string | null; gradeName: string | null; unitCode: string | null; planned: string | number;
  cumulative: string | number | null; balance: string | number; lastRatePerHour: string | number | null; estimatedCompletion: string | null;
  completed: boolean; readings: CargoReading[];
}
export interface CargoReadingInput {
  readAt: string; cumulative: string; unitCode: string; kind: string; reason: string | null; certifiedDocumentId: string | null; remarks: string | null;
}

export interface FileRef { id: string; fileId: string; name: string; contentType: string; scanState: string }
export interface Delay {
  id: string; categoryCode: string; categoryLabel: string; startedAt: string; endedAt: string | null; description: string;
  status: 'ACTIVE' | 'VOIDED'; voidReason: string | null; recordedByName: string | null; recordedAt: string; files: FileRef[];
  corrected: boolean; version: number;
}
export interface DelayInput { categoryCode: string; startedAt: string; endedAt: string | null; description: string; reason?: string | null }
export interface IncidentAction { id: string; text: string; ownerName: string | null; dueDate: string | null; doneAt: string | null }
export interface Incident {
  id: string; categoryCode: string; categoryLabel: string; severityCode: string; severityLabel: string; occurredAt: string;
  description: string; immediateActions: string | null; status: 'REPORTED' | 'UNDER_REVIEW' | 'CLOSED'; reportedByName: string | null;
  reportedAt: string; reviewStartedByName: string | null; reviewStartedAt: string | null; closedByName: string | null; closedAt: string | null;
  closingNote: string | null; actions: IncidentAction[]; files: FileRef[]; corrected: boolean; canReview: boolean; canClose: boolean; version: number;
}
export interface IncidentInput {
  categoryCode: string; severityCode: string; occurredAt: string; description: string; immediateActions: string | null;
  actions: { text: string; ownerName: string | null; dueDate: string | null; done: boolean }[]; reason?: string | null;
}

export interface OperationsOverview {
  jobId: string; jobNumber: string; timezone: string; vessels: VesselEta[]; eventTypes: ListItem[]; events: OperationEvent[];
  cargo: CargoGrade[]; delays: Delay[]; incidents: Incident[]; delayCategories: ListItem[]; incidentCategories: ListItem[];
  incidentSeverities: ListItem[]; sofRevisions: SofRevision[];
  permissions: { write: boolean; approve: boolean; cargoWrite: boolean; cargoCorrect: boolean; incidentRead: boolean; incidentWrite: boolean;
    incidentReview: boolean };
}

export interface OperationsJob {
  jobId: string; jobNumber: string; status: string; motherVessel: string | null; daughterVessel: string | null;
  lastEventLabel: string | null; lastEventAt: string | null; openIncidents: number;
}

export type OperationsList = 'event-types' | 'delay-categories' | 'incident-categories' | 'incident-severities';

@Injectable({ providedIn: 'root' })
export class OperationsService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private path(jobId: string) { return `${this.base}/jobs/${jobId}/operations`; }

  overview(jobId: string): Observable<OperationsOverview> { return this.http.get<OperationsOverview>(this.path(jobId)); }
  jobs(q: string, page: number, size: number): Observable<Page<OperationsJob>> {
    return this.http.get<Page<OperationsJob>>(`${this.base}/operations/jobs`, { params: toHttpParams({ q, page, size }) });
  }

  recordEta(jobId: string, input: { jobVesselId: string; etaUtc: string; timezone: string; source: string; note: string | null }): Observable<VesselEta[]> {
    return this.http.post<VesselEta[]>(`${this.path(jobId)}/eta`, input);
  }

  createEvent(jobId: string, input: EventInput): Observable<OperationEvent> { return this.http.post<OperationEvent>(`${this.path(jobId)}/events`, input); }
  correctEvent(jobId: string, event: OperationEvent, input: EventInput): Observable<OperationEvent> {
    return this.http.put<OperationEvent>(`${this.path(jobId)}/events/${event.id}`, input, { headers: { 'If-Match': ifMatch(event.version) } });
  }
  voidEvent(jobId: string, event: OperationEvent, reason: string): Observable<OperationEvent> {
    return this.http.post<OperationEvent>(`${this.path(jobId)}/events/${event.id}/void`, { reason }, { headers: { 'If-Match': ifMatch(event.version) } });
  }
  history(jobId: string, kind: 'events' | 'delays' | 'incidents', id: string): Observable<Change[]> {
    return this.http.get<Change[]>(`${this.path(jobId)}/${kind}/${id}/history`);
  }

  prepareSof(jobId: string): Observable<SofRevision[]> { return this.http.post<SofRevision[]>(`${this.path(jobId)}/sof`, null); }
  sofAction(jobId: string, sof: SofRevision, action: 'issue' | 'withdraw'): Observable<SofRevision[]> {
    return this.http.post<SofRevision[]>(`${this.path(jobId)}/sof/${sof.id}/${action}`, null, { headers: { 'If-Match': ifMatch(sof.version) } });
  }
  sofPdfUrl(jobId: string, sof: SofRevision | null): string { return `${this.path(jobId)}/sof/${sof ? sof.id : 'draft'}/pdf`; }

  addReading(jobId: string, lineNo: number, input: CargoReadingInput): Observable<CargoGrade> {
    return this.http.post<CargoGrade>(`${this.path(jobId)}/cargo/${lineNo}/readings`, input);
  }

  createDelay(jobId: string, input: DelayInput): Observable<Delay> { return this.http.post<Delay>(`${this.path(jobId)}/delays`, input); }
  updateDelay(jobId: string, delay: Delay, input: DelayInput): Observable<Delay> {
    return this.http.put<Delay>(`${this.path(jobId)}/delays/${delay.id}`, input, { headers: { 'If-Match': ifMatch(delay.version) } });
  }
  voidDelay(jobId: string, delay: Delay, reason: string): Observable<Delay> {
    return this.http.post<Delay>(`${this.path(jobId)}/delays/${delay.id}/void`, { reason }, { headers: { 'If-Match': ifMatch(delay.version) } });
  }
  createIncident(jobId: string, input: IncidentInput): Observable<Incident> { return this.http.post<Incident>(`${this.path(jobId)}/incidents`, input); }
  updateIncident(jobId: string, incident: Incident, input: IncidentInput): Observable<Incident> {
    return this.http.put<Incident>(`${this.path(jobId)}/incidents/${incident.id}`, input, { headers: { 'If-Match': ifMatch(incident.version) } });
  }
  reviewIncident(jobId: string, incident: Incident, decision: 'START_REVIEW' | 'CLOSE', note: string | null): Observable<Incident> {
    return this.http.post<Incident>(`${this.path(jobId)}/incidents/${incident.id}/review`, { decision, note },
      { headers: { 'If-Match': ifMatch(incident.version) } });
  }
  attach(jobId: string, ownerType: 'DELAY' | 'INCIDENT', ownerId: string, file: File): Observable<FileRef> {
    const form = new FormData();
    form.append('ownerType', ownerType);
    form.append('ownerId', ownerId);
    form.append('file', file, file.name);
    return this.http.post<FileRef>(`${this.path(jobId)}/files`, form);
  }

  list(name: OperationsList): Observable<ListItem[]> { return this.http.get<ListItem[]>(`${this.base}/operations/lists/${name}`); }
  saveListItem(name: OperationsList, input: Omit<ListItem, 'version'>, current?: ListItem): Observable<ListItem> {
    if (current) {
      return this.http.put<ListItem>(`${this.base}/operations/lists/${name}/${current.code}`, input, { headers: { 'If-Match': ifMatch(current.version) } });
    }
    return this.http.post<ListItem>(`${this.base}/operations/lists/${name}`, input);
  }
}
