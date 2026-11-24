import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { API_BASE_URL, Page, ifMatch } from '../../core/api/api.models';

export interface MovementContext { jobNumber: string; timeZone: string }
export interface MovementAsset { allocationId: string; assetId: string; identifier: string; category: string }
export interface MovementFile { id: string; name: string; scanState: string }
export interface Movement { id: string; jobId: string; craftId: string | null; craftIdentifier: string | null; vendorId: string | null;
  vendorName: string | null; departedAt: string; returnedAt: string | null; timeZone: string; assets: MovementAsset[];
  serviceNotes: string | null; standbyNotes: string | null; delayNotes: string | null; damageNotes: string | null;
  status: string; files: MovementFile[]; pendingAction: string | null; pendingJson: string | null; pendingReason: string | null;
  pendingBy: string | null; version: number }
export interface MovementInput { craftId: string | null; departedAt: string; returnedAt: string | null; allocationIds: string[];
  serviceNotes: string | null; standbyNotes: string | null; delayNotes: string | null; damageNotes: string | null; reason?: string }
export interface ClockResult { startMovementId: string | null; stopMovementId: string | null; startAt: string | null;
  stopAt: string | null; basisHours: number; elapsedHours: number | null; extraHours: number | null;
  tariffTermsStatus: string; billableExtraUnits: number | null; additionalCharge: number | null;
  currency: string | null; final: boolean }
export interface ClockSelection { startMovementId: string; stopMovementId: string | null; basisHours: number;
  overrideReason: string | null; pendingStartId: string | null; pendingStopId: string | null;
  pendingReason: string | null; requestedBy: string | null; version: number }
export interface Notice { id: string; status: string; snapshotJson: string; deliveryOutcome: string | null; version: number }
export interface Adjustment { id: string; hours: number; reason: string; status: string; requestedBy: string;
  approvedBy: string | null; version: number }
export interface ClockOverview { clock: ClockResult; selection: ClockSelection | null; expiryAt: string | null;
  countdownSeconds: number | null; exceeded: boolean; forecastCompletion: string | null;
  approvedAdjustmentHours: number; notice: Notice | null; adjustments: Adjustment[] }
export interface RecoveryAsset { allocationId: string; identifier: string; category: string; status: string;
  notes: string | null; fileId: string | null; resolutionReason: string | null; resolvedBy: string | null; version: number }
export interface Recovery { jobId: string; disembarkedAt: string | null; status: string;
  assets: RecoveryAsset[]; unresolved: string[]; version: number }

@Injectable({ providedIn: 'root' })
export class MovementsService {
  private readonly http = inject(HttpClient);
  private readonly jobs = `${inject(API_BASE_URL)}/jobs`;
  private root(jobId: string) { return `${this.jobs}/${jobId}/movements`; }
  private recoveryRoot(jobId: string) { return `${this.jobs}/${jobId}/recovery`; }
  context(jobId: string) { return this.http.get<MovementContext>(`${this.root(jobId)}/context`); }
  list(jobId: string, page = 0) { return this.http.get<Page<Movement>>(this.root(jobId), { params: { page, size: 25 } }); }
  dispatch(jobId: string, departedAt: string) {
    return this.http.post<Movement>(`${this.root(jobId)}/dispatch`, { departedAt });
  }
  correct(jobId: string, row: Movement, input: MovementInput) { return this.http.put<Movement>(`${this.root(jobId)}/${row.id}`, input,
    { headers: { 'If-Match': ifMatch(row.version) } }); }
  returned(jobId: string, row: Movement, returnedAt: string) { return this.http.post<Movement>(`${this.root(jobId)}/${row.id}/return`,
    { returnedAt }, { headers: { 'If-Match': ifMatch(row.version) } }); }
  void(jobId: string, row: Movement, reason: string) { return this.http.post<Movement>(`${this.root(jobId)}/${row.id}/void`,
    { reason }, { headers: { 'If-Match': ifMatch(row.version) } }); }
  decideMovement(jobId: string, row: Movement, decision: string, reason: string) {
    return this.http.post<Movement>(`${this.root(jobId)}/${row.id}/decision`, { decision, reason },
      { headers: { 'If-Match': ifMatch(row.version) } });
  }
  history(jobId: string, id: string) { return this.http.get<{ beforeJson: string; reason: string; changedAt: string }[]>(
    `${this.root(jobId)}/${id}/history`); }
  movementFile(jobId: string, id: string, file: File) { const body = new FormData(); body.append('file', file);
    return this.http.post<MovementFile>(`${this.root(jobId)}/${id}/files`, body); }
  clock(jobId: string) { return this.http.get<ClockOverview>(`${this.root(jobId)}/clock`); }
  select(jobId: string, selection: ClockSelection | null, startMovementId: string, stopMovementId: string | null, reason: string) {
    return this.http.post<ClockSelection>(`${this.root(jobId)}/clock/selection`, { startMovementId, stopMovementId, reason },
      selection ? { headers: { 'If-Match': ifMatch(selection.version) } } : {});
  }
  decide(jobId: string, selection: ClockSelection, decision: string, reason: string) {
    return this.http.post<ClockSelection>(`${this.root(jobId)}/clock/decision`, { decision, reason },
      { headers: { 'If-Match': ifMatch(selection.version) } });
  }
  basis(jobId: string, selection: ClockSelection, basisHours: number, reason: string) {
    return this.http.post<ClockSelection>(`${this.root(jobId)}/clock/basis`, { basisHours, reason },
      { headers: { 'If-Match': ifMatch(selection.version) } });
  }
  adjustment(jobId: string, hours: string, reason: string) {
    return this.http.post<Adjustment>(`${this.root(jobId)}/clock/adjustments`, { hours, reason });
  }
  decideAdjustment(jobId: string, row: Adjustment, decision: string, reason: string) {
    return this.http.post<Adjustment>(`${this.root(jobId)}/clock/adjustments/${row.id}/decision`, { decision, reason },
      { headers: { 'If-Match': ifMatch(row.version) } });
  }
  release(jobId: string, notice: Notice) { return this.http.post<Notice>(`${this.root(jobId)}/clock/notice/release`, {},
    { headers: { 'If-Match': ifMatch(notice.version) } }); }
  delivery(jobId: string, notice: Notice, outcome: string) { return this.http.post<Notice>(`${this.root(jobId)}/clock/notice/delivery`,
    { outcome }, { headers: { 'If-Match': ifMatch(notice.version) } }); }
  recovery(jobId: string) { return this.http.get<Recovery>(this.recoveryRoot(jobId)); }
  disembark(jobId: string, row: Recovery, at: string, reason: string) { return this.http.post<Recovery>(`${this.recoveryRoot(jobId)}/disembark`,
    { at, reason }, { headers: { 'If-Match': ifMatch(row.version) } }); }
  record(jobId: string, row: RecoveryAsset, status: string, notes: string, reason: string) {
    return this.http.put<Recovery>(`${this.recoveryRoot(jobId)}/assets/${row.allocationId}`, { status, notes, reason },
      row.status === 'UNRECORDED' ? {} : { headers: { 'If-Match': ifMatch(row.version) } });
  }
  resolve(jobId: string, row: RecoveryAsset, reason: string) { return this.http.post<Recovery>(
    `${this.recoveryRoot(jobId)}/assets/${row.allocationId}/resolve`, { reason },
    { headers: { 'If-Match': ifMatch(row.version) } }); }
  recoveryFile(jobId: string, row: RecoveryAsset, file: File) { const body = new FormData(); body.append('file', file);
    return this.http.post<Recovery>(`${this.recoveryRoot(jobId)}/assets/${row.allocationId}/files`, body); }
  close(jobId: string, row: Recovery) { return this.http.post<Recovery>(`${this.recoveryRoot(jobId)}/close`, {},
    { headers: { 'If-Match': ifMatch(row.version) } }); }
}
