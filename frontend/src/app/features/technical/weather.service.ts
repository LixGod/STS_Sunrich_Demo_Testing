import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../core/api/api.models';

export type LimitSetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'SUPERSEDED';
export type LimitAction = 'EDIT' | 'SUBMIT' | 'APPROVE' | 'RETURN';
export type Comparison = 'ABOVE' | 'BELOW';
export type ReportKind = 'FORECAST' | 'OBSERVATION';
export type ReportSource = 'PROVIDER_REPORT' | 'OTHER_PROVIDER' | 'OBSERVATION';

export interface WeatherParameter { code: string; label: string; unit: string; sortOrder: number; active: boolean; version: number }

export interface LimitRowInput { parameterCode: string; operation: string; comparison: Comparison; threshold: string | null; action: string }
export interface LimitRow {
  parameterCode: string; parameterLabel: string; unit: string; operation: string; comparison: Comparison; threshold: string | number; action: string;
}

export interface LimitSet {
  id: string; revisionNo: number; status: LimitSetStatus; rows: LimitRow[];
  submittedByName: string | null; submittedAt: string | null; decidedByName: string | null; decidedAt: string | null;
  returnedReason: string | null; allowedActions: LimitAction[]; updatedAt: string | null; version: number;
}

export interface LimitOverview {
  jobId: string; jobNumber: string;
  revisions: { id: string; revisionNo: number; status: LimitSetStatus; decidedAt: string | null }[];
  approved: LimitSet | null; canCreate: boolean;
}

export interface WeatherValue { at: string; parameterCode: string; value: string | number }

export interface WeatherReportInput {
  kind: ReportKind; source: ReportSource; providerName: string | null; issuedAt: string | null; validFrom: string | null; validTo: string | null;
  observedAt: string | null; area: string | null; notes: string | null; values: { at: string; parameterCode: string; value: string }[];
}

export interface WeatherReport {
  id: string; kind: ReportKind; source: ReportSource; providerName: string | null; issuedAt: string | null; validFrom: string | null;
  validTo: string | null; observedAt: string | null; area: string | null; notes: string | null;
  file: { id: string; name: string; contentType: string; scanState: string } | null;
  values: WeatherValue[]; status: 'RECORDED' | 'ACCEPTED' | 'REJECTED'; recordedByName: string | null; recordedAt: string;
  reviewedByName: string | null; reviewedAt: string | null; reviewReason: string | null; expired: boolean;
  canEdit: boolean; canReview: boolean; version: number;
}

export interface WeatherWarning {
  reportId: string; kind: ReportKind; reportStatus: string; at: string; parameterCode: string; parameterLabel: string; unit: string;
  value: string | number; comparison: Comparison; threshold: string | number; operation: string; action: string; message: string;
}

export interface WeatherOverview {
  jobId: string; jobNumber: string; timezone: string; parameters: WeatherParameter[]; reports: WeatherReport[];
  warnings: WeatherWarning[]; limitsApproved: boolean; canRecord: boolean;
}

@Injectable({ providedIn: 'root' })
export class WeatherService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private limitsPath(jobId: string) { return `${this.base}/jobs/${jobId}/operating-limits`; }
  private weatherPath(jobId: string) { return `${this.base}/jobs/${jobId}/weather`; }

  limits(jobId: string): Observable<LimitOverview> { return this.http.get<LimitOverview>(this.limitsPath(jobId)); }
  limitSet(jobId: string, id: string): Observable<LimitSet> { return this.http.get<LimitSet>(`${this.limitsPath(jobId)}/revisions/${id}`); }
  createLimits(jobId: string): Observable<LimitSet> { return this.http.post<LimitSet>(`${this.limitsPath(jobId)}/revisions`, null); }
  saveLimits(jobId: string, set: LimitSet, rows: LimitRowInput[]): Observable<LimitSet> {
    return this.http.put<LimitSet>(`${this.limitsPath(jobId)}/revisions/${set.id}`, { rows }, { headers: { 'If-Match': ifMatch(set.version) } });
  }
  submitLimits(jobId: string, set: LimitSet): Observable<LimitSet> {
    return this.http.post<LimitSet>(`${this.limitsPath(jobId)}/revisions/${set.id}/submit`, null, { headers: { 'If-Match': ifMatch(set.version) } });
  }
  decideLimits(jobId: string, set: LimitSet, decision: 'APPROVE' | 'RETURN', reason: string | null): Observable<LimitSet> {
    return this.http.post<LimitSet>(`${this.limitsPath(jobId)}/revisions/${set.id}/decision`, { decision, reason },
      { headers: { 'If-Match': ifMatch(set.version) } });
  }

  weather(jobId: string): Observable<WeatherOverview> { return this.http.get<WeatherOverview>(this.weatherPath(jobId)); }
  recordReport(jobId: string, report: WeatherReportInput, file: File | null): Observable<WeatherReport> {
    const form = new FormData();
    form.append('report', JSON.stringify(report)); // a text part; the server reads it as JSON
    if (file) form.append('file', file, file.name);
    return this.http.post<WeatherReport>(`${this.weatherPath(jobId)}/reports`, form);
  }
  updateReport(jobId: string, report: WeatherReport, input: WeatherReportInput): Observable<WeatherReport> {
    return this.http.put<WeatherReport>(`${this.weatherPath(jobId)}/reports/${report.id}`, input, { headers: { 'If-Match': ifMatch(report.version) } });
  }
  reviewReport(jobId: string, report: WeatherReport, decision: 'ACCEPT' | 'REJECT', reason: string | null): Observable<WeatherReport> {
    return this.http.post<WeatherReport>(`${this.weatherPath(jobId)}/reports/${report.id}/review`, { decision, reason },
      { headers: { 'If-Match': ifMatch(report.version) } });
  }

  parameters(): Observable<WeatherParameter[]> { return this.http.get<WeatherParameter[]>(`${this.base}/technical/weather-parameters`); }
  saveParameter(input: Omit<WeatherParameter, 'version'>, current?: WeatherParameter): Observable<WeatherParameter> {
    if (current) {
      return this.http.put<WeatherParameter>(`${this.base}/technical/weather-parameters/${current.code}`, input,
        { headers: { 'If-Match': ifMatch(current.version) } });
    }
    return this.http.post<WeatherParameter>(`${this.base}/technical/weather-parameters`, input);
  }
}
