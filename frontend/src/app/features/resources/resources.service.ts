import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, PageQuery, ifMatch } from '../../core/api/api.models';

export interface CodeRow { code: string; label: string; active: boolean; version: number; costHeadId?: string | null }
export interface ResourcePolicy { mode: 'BLOCK' | 'WARN'; warningDays: number | null;
  requirementsConfigured: boolean; version: number }
export interface Requirement { id: string; resourceKind: string; scopeCode: string;
  certificateTypeCode: string; active: boolean; version: number }
export interface EquipmentModel { id: string; categoryCode: string; code: string; name: string;
  active: boolean; version: number; standardRate: number | null; standardRateCurrency: string | null }
export type AssetStatus = 'AVAILABLE' | 'MAINTENANCE' | 'DAMAGED' | 'LOST' | 'RETIRED';
/** Stock per model: revised = original minus damaged, lost and retired units. */
export interface InventoryRow { modelId: string; modelCode: string; modelName: string; categoryCode: string; categoryLabel: string;
  original: number; inUse: number; available: number; maintenance: number; damaged: number; lost: number; retired: number; revised: number }
export interface InventoryEvent { jobId: string; jobNumber: string; result: 'DAMAGED' | 'SHORT'; notes: string | null;
  resolution: string | null; at: string }
export interface InventoryUnit { assetId: string; assetIdentifier: string; status: AssetStatus; inUse: boolean;
  jobId: string | null; jobNumber: string | null; history: InventoryEvent[] }
export interface Asset { id: string; branchId: string | null; modelId: string; categoryCode: string;
  assetIdentifier: string; dimensionsText: string | null; swlValue: number | null; swlUnit: string | null;
  swlSource: string | null; mblValue: number | null; mblUnit: string | null; mblSource: string | null;
  homeLocation: string | null; status: AssetStatus; version: number }
export interface Person { id: string; personnelIdentifier: string; fullName: string;
  appUserId: string | null; availabilityNote: string | null; active: boolean; competencies: string[]; version: number }
export interface Vendor { id: string; name: string; contacts: string | null;
  contractFrom: string | null; contractTo: string | null; active: boolean; version: number }
export interface Craft { id: string; vendorId: string; identifier: string; name: string;
  craftType: 'TUG' | 'LAUNCH' | 'WORKBOAT'; technicalData: string | null; bollardPullValue: number | null;
  bollardPullUnit: string | null; bollardPullSource: string | null; homeLocation: string | null;
  active: boolean; version: number }
export interface Certificate { id: string; ownerKind: string; ownerId: string; typeCode: string;
  certificateNumber: string; issuer: string; issueDate: string; expiryDate: string | null;
  fileId: string | null; fileName: string | null; scanState: string | null; version: number }
export interface Inspection { id: string; assetId: string; inspectedAt: string; result: string; source: string;
  nextDueAt: string | null; version: number }
export interface ResourceChange { occurredAt: string; actorName: string | null; details: string | null }
export interface ResourceJobContext { jobId: string; jobNumber: string; timeZone: string }
export interface ResourceChoice { id: string; identifier: string; label: string; category: string | null }
export interface ResourceChoices { assets: ResourceChoice[]; people: ResourceChoice[]; craft: ResourceChoice[]; policy: ResourcePolicy }
export interface Allocation { id: string; jobId: string; kind: 'ASSET' | 'PERSON' | 'CRAFT'; resourceId: string;
  identifier: string; category: string | null; startAt: string; endAt: string; timeZone: string;
  status: 'ACTIVE' | 'PENDING_EXCEPTION' | 'REJECTED' | 'CANCELLED'; collisionIds: string[];
  eligibilityMessages: string[]; exceptionReason: string | null; requestedBy: string | null;
  approvedBy: string | null; decisionReason: string | null; version: number }
export interface AllocationInput { kind: 'ASSET' | 'PERSON' | 'CRAFT'; resourceId: string;
  startAt: string; endAt: string; exceptionReason: string | null }

@Injectable({ providedIn: 'root' })
export class ResourcesService {
  private readonly http = inject(HttpClient);
  private readonly root = `${inject(API_BASE_URL)}/resources`;
  private readonly jobs = `${inject(API_BASE_URL)}/jobs`;

  codes(kind: 'categories' | 'competencies' | 'certificate-types'): Observable<CodeRow[]> {
    return this.http.get<CodeRow[]>(`${this.root}/${kind}`);
  }
  saveCode(kind: 'categories' | 'competencies' | 'certificate-types', row: Omit<CodeRow, 'version'>, version?: number): Observable<CodeRow> {
    return version === undefined ? this.http.post<CodeRow>(`${this.root}/${kind}`, row) :
      this.http.put<CodeRow>(`${this.root}/${kind}/${row.code}`, row, { headers: { 'If-Match': ifMatch(version) } });
  }
  private listParams(query: PageQuery): HttpParams {
    let params = new HttpParams().set('page', query.page).set('size', query.size);
    if (query.q) params = params.set('q', query.q);
    if (query.sort) params = params.set('sort', query.sort);
    return params;
  }
  models(query: PageQuery = { page: 0, size: 25 }): Observable<Page<EquipmentModel>> {
    return this.http.get<Page<EquipmentModel>>(`${this.root}/models`, { params: this.listParams(query) });
  }
  model(id: string): Observable<EquipmentModel> { return this.http.get<EquipmentModel>(`${this.root}/models/${id}`); }
  saveModel(row: Omit<EquipmentModel, 'id' | 'version'>, id?: string, version?: number): Observable<EquipmentModel> {
    return id ? this.http.put<EquipmentModel>(`${this.root}/models/${id}`, row, { headers: { 'If-Match': ifMatch(version!) } }) :
      this.http.post<EquipmentModel>(`${this.root}/models`, row);
  }
  inventory(): Observable<InventoryRow[]> {
    return this.http.get<InventoryRow[]>(`${this.root}/inventory`);
  }
  inventoryUnits(modelId: string): Observable<InventoryUnit[]> {
    return this.http.get<InventoryUnit[]>(`${this.root}/inventory/${modelId}`);
  }
  assets(query: PageQuery = { page: 0, size: 25 }): Observable<Page<Asset>> {
    return this.http.get<Page<Asset>>(`${this.root}/assets`, { params: this.listParams(query) });
  }
  saveAsset(row: Omit<Asset, 'id' | 'categoryCode' | 'version'>, id?: string, version?: number): Observable<Asset> {
    return id ? this.http.put<Asset>(`${this.root}/assets/${id}`, row, { headers: { 'If-Match': ifMatch(version!) } }) :
      this.http.post<Asset>(`${this.root}/assets`, row);
  }
  people(query: PageQuery = { page: 0, size: 25 }): Observable<Page<Person>> {
    return this.http.get<Page<Person>>(`${this.root}/personnel`, { params: this.listParams(query) });
  }
  savePerson(row: Omit<Person, 'id' | 'version'>, id?: string, version?: number): Observable<Person> {
    return id ? this.http.put<Person>(`${this.root}/personnel/${id}`, row, { headers: { 'If-Match': ifMatch(version!) } }) :
      this.http.post<Person>(`${this.root}/personnel`, row);
  }
  vendors(query: PageQuery = { page: 0, size: 25 }): Observable<Page<Vendor>> {
    return this.http.get<Page<Vendor>>(`${this.root}/vendors`, { params: this.listParams(query) });
  }
  vendor(id: string): Observable<Vendor> { return this.http.get<Vendor>(`${this.root}/vendors/${id}`); }
  saveVendor(row: Omit<Vendor, 'id' | 'version'>, id?: string, version?: number): Observable<Vendor> {
    return id ? this.http.put<Vendor>(`${this.root}/vendors/${id}`, row, { headers: { 'If-Match': ifMatch(version!) } }) :
      this.http.post<Vendor>(`${this.root}/vendors`, row);
  }
  craft(query: PageQuery = { page: 0, size: 25 }): Observable<Page<Craft>> {
    return this.http.get<Page<Craft>>(`${this.root}/craft`, { params: this.listParams(query) });
  }
  saveCraft(row: Omit<Craft, 'id' | 'version'>, id?: string, version?: number): Observable<Craft> {
    return id ? this.http.put<Craft>(`${this.root}/craft/${id}`, row, { headers: { 'If-Match': ifMatch(version!) } }) :
      this.http.post<Craft>(`${this.root}/craft`, row);
  }
  policy(): Observable<ResourcePolicy> {
    return this.http.get<ResourcePolicy>(`${this.root}/policy`);
  }
  savePolicy(row: ResourcePolicy): Observable<ResourcePolicy> {
    return this.http.put<ResourcePolicy>(`${this.root}/policy`, row, { headers: { 'If-Match': ifMatch(row.version) } });
  }
  requirements(): Observable<Requirement[]> {
    return this.http.get<Requirement[]>(`${this.root}/requirements`);
  }
  addRequirement(row: Pick<Requirement, 'resourceKind' | 'scopeCode' | 'certificateTypeCode' | 'active'>): Observable<Requirement> {
    return this.http.post<Requirement>(`${this.root}/requirements`, row);
  }
  certificates(kind: 'assets' | 'personnel' | 'craft', id: string): Observable<Certificate[]> {
    return this.http.get<Certificate[]>(`${this.root}/${kind}/${id}/certificates`);
  }
  addCertificate(kind: 'assets' | 'personnel' | 'craft', id: string, row: object, file: File | null): Observable<Certificate> {
    const body = new FormData(); body.append('certificate', new Blob([JSON.stringify(row)], { type: 'application/json' }));
    if (file) body.append('file', file);
    return this.http.post<Certificate>(`${this.root}/${kind}/${id}/certificates`, body);
  }
  editCertificate(kind: 'assets' | 'personnel' | 'craft', ownerId: string, row: Certificate, input: object,
    file: File | null): Observable<Certificate> {
    if (!file) return this.http.put<Certificate>(`${this.root}/${kind}/${ownerId}/certificates/${row.id}`, input,
      { headers: { 'If-Match': ifMatch(row.version) } });
    const body = new FormData(); body.append('certificate', new Blob([JSON.stringify(input)], { type: 'application/json' }));
    body.append('file', file);
    return this.http.put<Certificate>(`${this.root}/${kind}/${ownerId}/certificates/${row.id}`, body,
      { headers: { 'If-Match': ifMatch(row.version) } });
  }
  certificateHistory(kind: 'assets' | 'personnel' | 'craft', ownerId: string, id: string): Observable<ResourceChange[]> {
    return this.http.get<ResourceChange[]>(`${this.root}/${kind}/${ownerId}/certificates/${id}/history`);
  }
  inspections(assetId: string): Observable<Inspection[]> { return this.http.get<Inspection[]>(`${this.root}/assets/${assetId}/inspections`); }
  addInspection(assetId: string, row: object): Observable<Inspection> {
    return this.http.post<Inspection>(`${this.root}/assets/${assetId}/inspections`, row);
  }
  editInspection(assetId: string, row: Inspection, input: object): Observable<Inspection> {
    return this.http.put<Inspection>(`${this.root}/assets/${assetId}/inspections/${row.id}`, input,
      { headers: { 'If-Match': ifMatch(row.version) } });
  }
  inspectionHistory(assetId: string, id: string): Observable<ResourceChange[]> {
    return this.http.get<ResourceChange[]>(`${this.root}/assets/${assetId}/inspections/${id}/history`);
  }
  context(jobId: string): Observable<ResourceJobContext> {
    return this.http.get<ResourceJobContext>(`${this.jobs}/${jobId}/resources/allocations/context`);
  }
  choices(jobId: string, q = ''): Observable<ResourceChoices> {
    return this.http.get<ResourceChoices>(`${this.jobs}/${jobId}/resources/allocations/choices`, { params: { q } });
  }
  allocations(jobId: string, page = 0): Observable<Page<Allocation>> {
    return this.http.get<Page<Allocation>>(`${this.jobs}/${jobId}/resources/allocations`, { params: new HttpParams().set('page', page).set('size', 25) });
  }
  createAllocation(jobId: string, row: AllocationInput): Observable<Allocation> {
    return this.http.post<Allocation>(`${this.jobs}/${jobId}/resources/allocations`, row);
  }
  amendAllocation(jobId: string, row: Allocation, input: AllocationInput): Observable<Allocation> {
    return this.http.put<Allocation>(`${this.jobs}/${jobId}/resources/allocations/${row.id}`, input, { headers: { 'If-Match': ifMatch(row.version) } });
  }
  decideAllocation(jobId: string, row: Allocation, decision: 'APPROVE' | 'RETURN', reason: string): Observable<Allocation> {
    return this.http.post<Allocation>(`${this.jobs}/${jobId}/resources/allocations/${row.id}/decision`, { decision, reason }, { headers: { 'If-Match': ifMatch(row.version) } });
  }
  cancelAllocation(jobId: string, row: Allocation, reason: string): Observable<Allocation> {
    return this.http.post<Allocation>(`${this.jobs}/${jobId}/resources/allocations/${row.id}/cancel`, { reason }, { headers: { 'If-Match': ifMatch(row.version) } });
  }
}
