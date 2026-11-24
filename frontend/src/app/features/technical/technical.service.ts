import { HttpClient, HttpContext, HttpEvent } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, PageQuery, SILENT_ERRORS, ifMatch, pageParams } from '../../core/api/api.models';

export type ScanState = 'NOT_SCANNED' | 'CLEAN' | 'INFECTED';
export type ParticularsStatus = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'SUPERSEDED';
export type ParticularsAction = 'EDIT' | 'SUBMIT' | 'VERIFY' | 'RETURN';

export interface VesselRow {
  id: string;
  imo: string;
  name: string;
  vesselType: string;
  loaM: number | null;
  beamM: number | null;
  dwtT: number | null;
  particularsStatus: 'UNVERIFIED' | 'VERIFIED';
  active: boolean;
  version: number | null;
}

export interface FileInfo {
  id: string;
  name: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  scanState: ScanState;
}

export interface VesselDocument {
  id: string;
  documentTypeId: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  versionNo: number;
  latest: boolean;
  title: string | null;
  file: FileInfo | null;
  uploadedByName: string | null;
  uploadedAt: string;
}

export interface ParticularField {
  code: string;
  label: string;
  group: 'DIMENSIONS' | 'MANIFOLD' | 'OTHER';
  dataType: 'NUMBER' | 'TEXT';
  unit: string | null;
  requiredForPlanning: boolean;
}

export interface ParticularValue {
  fieldCode: string;
  value: string;
  sourceDocumentId: string | null;
  sourcePage: number | null;
  verified: boolean;
}

export interface ParticularsWarning {
  code: string;
  fields: string[];
  message: string;
}

export interface Particulars {
  id: string;
  vesselId: string;
  versionNo: number;
  status: ParticularsStatus;
  notes: string | null;
  values: ParticularValue[];
  warnings: ParticularsWarning[];
  submittedByName: string | null;
  submittedAt: string | null;
  verifiedByName: string | null;
  verifiedAt: string | null;
  returnedReason: string | null;
  allowedActions: ParticularsAction[];
  version: number | null;
}

export interface ParticularsOverview {
  vesselId: string;
  versions: { id: string; versionNo: number; status: ParticularsStatus; verifiedAt: string | null }[];
  canCreateVersion: boolean;
}

export interface ParticularValueRequest {
  fieldCode: string;
  value: string;
  sourceDocumentId: string | null;
  sourcePage: number | null;
}

@Injectable({ providedIn: 'root' })
export class TechnicalService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  vessels(query: PageQuery): Observable<Page<VesselRow>> {
    return this.http.get<Page<VesselRow>>(`${this.base}/masters/vessels`, { params: pageParams(query) });
  }

  vessel(id: string): Observable<VesselRow> {
    return this.http.get<VesselRow>(`${this.base}/masters/vessels/${id}`);
  }

  documents(vesselId: string): Observable<VesselDocument[]> {
    return this.http.get<VesselDocument[]>(`${this.base}/technical/vessels/${vesselId}/documents`);
  }

  /** Multipart upload with progress events. */
  upload(vesselId: string, documentTypeId: string, title: string | null, file: File): Observable<HttpEvent<VesselDocument>> {
    const form = new FormData();
    form.append('documentTypeId', documentTypeId);
    if (title) form.append('title', title);
    form.append('file', file, file.name);
    return this.http.post<VesselDocument>(`${this.base}/technical/vessels/${vesselId}/documents`, form, {
      reportProgress: true,
      observe: 'events',
      context: this.silent,
    });
  }

  fields(): Observable<ParticularField[]> {
    return this.http.get<ParticularField[]>(`${this.base}/technical/particular-fields`);
  }

  overview(vesselId: string): Observable<ParticularsOverview> {
    return this.http.get<ParticularsOverview>(`${this.base}/technical/vessels/${vesselId}/particulars`);
  }

  particulars(id: string): Observable<Particulars> {
    return this.http.get<Particulars>(`${this.base}/technical/particulars/${id}`);
  }

  newVersion(vesselId: string): Observable<Particulars> {
    return this.http.post<Particulars>(`${this.base}/technical/vessels/${vesselId}/particulars`, null, { context: this.silent });
  }

  save(current: Particulars, notes: string | null, values: ParticularValueRequest[]): Observable<Particulars> {
    return this.http.put<Particulars>(`${this.base}/technical/particulars/${current.id}`, { notes, values }, this.options(current));
  }

  submit(current: Particulars): Observable<Particulars> {
    return this.http.post<Particulars>(`${this.base}/technical/particulars/${current.id}/submit`, null, this.options(current));
  }

  verify(current: Particulars, confirmedFieldCodes: string[]): Observable<Particulars> {
    return this.http.post<Particulars>(`${this.base}/technical/particulars/${current.id}/verify`, { confirmedFieldCodes }, this.options(current));
  }

  returnForCorrection(current: Particulars, reason: string): Observable<Particulars> {
    return this.http.post<Particulars>(`${this.base}/technical/particulars/${current.id}/return`, { reason }, this.options(current));
  }

  private options(current: Particulars) {
    return { headers: { 'If-Match': ifMatch(current.version) }, context: this.silent };
  }
}
