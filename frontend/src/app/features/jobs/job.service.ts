import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, PageQuery, ifMatch, pageParams } from '../../core/api/api.models';

export interface JobNamedRef {
  id: string;
  code: string | null;
  name: string;
}

export interface JobVessel {
  id: string;
  role: 'MOTHER' | 'DAUGHTER';
  vesselId: string;
  imo: string | null;
  name: string | null;
  etaUtc: string | null;
  etaTimezone: string | null;
}

export interface JobCargo {
  lineNo: number;
  cargoGradeCode: string | null;
  cargoGradeName: string | null;
  quantity: number;
  unitCode: string | null;
}

export interface TariffSnapshot {
  tariffCode: string;
  tariffName: string;
  currency: string;
  baseHours: number;
  baseCharge: number;
  extraHourRate: number;
  billingIncrementMinutes: number | null;
  graceMinutes: number | null;
  roundingRule: 'NONE' | 'ROUND_UP_TO_INCREMENT' | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  capturedAt: string;
}

export interface JobDetail {
  id: string;
  jobNumber: string;
  status: string;
  branchCode: string | null;
  enquiryId: string;
  enquiryReference: string;
  quotationReference: string;
  client: JobNamedRef | null;
  contact: JobNamedRef | null;
  operationType: string | null;
  currency: string;
  quotedAmount: number;
  vessels: JobVessel[];
  cargo: JobCargo[];
  tariff: TariffSnapshot | null;
  createdAt: string | null;
  version: number;
  flow: {
    documentTotal: number; documentReceived: number; preArrivalTotal: number; preArrivalReviewed: number;
    jpoStatus: string | null; jpoAcceptanceCount: number; mooringStatus: string | null; finalReportIssued: boolean;
    canOpenDocuments: boolean; canOpenJpo: boolean; canOpenMooring: boolean; canOpenWeather: boolean;
  };
}

export interface JobTariffTermsInput {
  billingIncrementMinutes: number;
  graceMinutes: number;
  roundingRule: 'NONE' | 'ROUND_UP_TO_INCREMENT';
  reason: string;
}

export interface JobSummary {
  id: string;
  jobNumber: string;
  status: string;
  branchCode: string | null;
  enquiryReference: string;
  clientName: string | null;
  motherVesselName: string | null;
  daughterVesselName: string | null;
  createdAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class JobService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/jobs`;

  list(query: PageQuery): Observable<Page<JobSummary>> {
    return this.http.get<Page<JobSummary>>(this.url, { params: pageParams(query) });
  }

  get(id: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${this.url}/${id}`);
  }

  updateTariffTerms(job: JobDetail, input: JobTariffTermsInput): Observable<JobDetail> {
    return this.http.put<JobDetail>(`${this.url}/${job.id}/tariff-terms`, input,
      { headers: { 'If-Match': ifMatch(job.version) } });
  }
}
