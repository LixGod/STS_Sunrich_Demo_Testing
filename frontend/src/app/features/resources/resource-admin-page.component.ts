import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { debounceTime, switchMap } from 'rxjs';
import { LookupOption, LookupService } from '../../core/lookups/lookup.service';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
import { PageQuery, problemKey, toProblem } from '../../core/api/api.models';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../shared/components/data-table/table.models';
import { FormErrorsComponent, applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { Asset, AssetStatus, Certificate, CodeRow, EquipmentModel, Inspection, Person, Requirement,
  ResourceChange, ResourcePolicy, ResourcesService, Vendor } from './resources.service';

type Mode = 'equipment' | 'personnel';
type CodeKind = 'categories' | 'competencies' | 'certificate-types';

@Component({
  selector: 's2s-resource-admin-page',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Message, PageHeaderComponent, DataTableComponent, FormErrorsComponent],
  templateUrl: './resource-admin-page.component.html',
  styleUrl: './resource-admin-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResourceAdminPageComponent {
  private readonly api = inject(ResourcesService);
  private readonly lookup = inject(LookupService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly mode = signal<Mode>((this.route.snapshot.data['resourceMode'] as Mode) ?? 'equipment');
  protected readonly modelSearch = new FormControl('', { nonNullable: true });
  protected readonly tableParams = signal<Record<string, string>>({});
  protected readonly categories = signal<CodeRow[]>([]);
  protected readonly costHeads = signal<LookupOption[]>([]);
  protected readonly currencies = signal<LookupOption[]>([]);
  protected readonly competencies = signal<CodeRow[]>([]);
  protected readonly certificateTypes = signal<CodeRow[]>([]);
  protected readonly models = signal<EquipmentModel[]>([]);
  protected readonly assets = signal<Asset[]>([]);
  protected readonly people = signal<Person[]>([]);
  protected readonly certificates = signal<Certificate[]>([]);
  protected readonly inspections = signal<Inspection[]>([]);
  protected readonly requirements = signal<Requirement[]>([]);
  protected readonly policy = signal<ResourcePolicy | null>(null);
  protected readonly selected = signal<{ kind: 'assets' | 'personnel'; id: string } | null>(null);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly busy = signal(false);
  protected readonly canWrite = this.auth.hasPermission(Permissions.masterResourceWrite);
  protected readonly file = signal<File | null>(null);
  protected readonly editing = signal<{ kind: string; id: string; version: number } | null>(null);
  protected readonly editingCertificate = signal<Certificate | null>(null);
  protected readonly editingInspection = signal<Inspection | null>(null);
  protected readonly changeHistory = signal<ResourceChange[]>([]);
  protected readonly modelTable = viewChild<DataTableComponent<EquipmentModel>>('modelTable');
  protected readonly assetTable = viewChild<DataTableComponent<Asset>>('assetTable');
  protected readonly personTable = viewChild<DataTableComponent<Person>>('personTable');
  protected readonly vendorTable = viewChild<DataTableComponent<Vendor>>('vendorTable');
  protected readonly modelColumns: ColumnDef<EquipmentModel>[] = [
    { key: 'code', headerKey: 'resources.code', sortField: 'code' },
    { key: 'name', headerKey: 'resources.name', sortField: 'name' },
    { key: 'categoryCode', headerKey: 'resources.category', sortField: 'categoryCode' },
  ];
  protected readonly assetColumns: ColumnDef<Asset>[] = [
    { key: 'assetIdentifier', headerKey: 'resources.assetId', sortField: 'assetIdentifier' },
    { key: 'categoryCode', headerKey: 'resources.category', sortField: 'categoryCode' },
    { key: 'status', headerKey: 'resources.status', sortField: 'status' },
  ];
  protected readonly personColumns: ColumnDef<Person>[] = [
    { key: 'personnelIdentifier', headerKey: 'resources.personnelId', sortField: 'personnelIdentifier' },
    { key: 'fullName', headerKey: 'resources.fullName', sortField: 'fullName' },
  ];
  protected readonly vendorColumns: ColumnDef<Vendor>[] = [
    { key: 'name', headerKey: 'resources.name', sortField: 'name' },
    { key: 'contractFrom', headerKey: 'resources.contractFrom', sortField: 'contractFrom' },
  ];
  protected readonly modelActions: RowAction<EquipmentModel>[] = [{ icon: 'pi pi-pencil', labelKey: 'common.edit',
    permission: Permissions.masterResourceWrite, run: row => this.editModel(row) }];
  protected readonly assetActions: RowAction<Asset>[] = [
    { icon: 'pi pi-pencil', labelKey: 'common.edit', permission: Permissions.masterResourceWrite, run: row => this.editAsset(row) },
    { icon: 'pi pi-file', labelKey: 'resources.certificates', run: row => this.select('assets', row.id) },
  ];
  protected readonly personActions: RowAction<Person>[] = [
    { icon: 'pi pi-pencil', labelKey: 'common.edit', permission: Permissions.masterResourceWrite, run: row => this.editPerson(row) },
    { icon: 'pi pi-file', labelKey: 'resources.certificates', run: row => this.select('personnel', row.id) },
  ];
  protected readonly vendorActions: RowAction<Vendor>[] = [{ icon: 'pi pi-pencil', labelKey: 'common.edit',
    permission: Permissions.masterResourceWrite, run: row => this.editVendor(row) }];
  protected readonly loadModels = (query: PageQuery) => this.api.models(query);
  protected readonly loadAssets = (query: PageQuery) => this.api.assets(query);
  protected readonly loadPeople = (query: PageQuery) => this.api.people(query);
  protected readonly loadVendors = (query: PageQuery) => this.api.vendors(query);

  protected readonly codeForm = new FormGroup({ kind: new FormControl<CodeKind>('categories', { nonNullable: true }),
    code: new FormControl('', { nonNullable: true, validators: Validators.required }),
    label: new FormControl('', { nonNullable: true, validators: Validators.required }),
    active: new FormControl(true, { nonNullable: true }), costHeadId: new FormControl('', { nonNullable: true }) });
  protected readonly modelForm = new FormGroup({ categoryCode: new FormControl('', { nonNullable: true, validators: Validators.required }),
    code: new FormControl('', { nonNullable: true, validators: Validators.required }),
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    active: new FormControl(true, { nonNullable: true }), standardRate: new FormControl<number | null>(null),
    standardRateCurrency: new FormControl('USD', { nonNullable: true }) });
  protected readonly assetForm = new FormGroup({ modelId: new FormControl('', { nonNullable: true, validators: Validators.required }),
    assetIdentifier: new FormControl('', { nonNullable: true, validators: Validators.required }),
    dimensionsText: new FormControl('', { nonNullable: true }), status: new FormControl<AssetStatus>('AVAILABLE', { nonNullable: true }),
    homeLocation: new FormControl('', { nonNullable: true }), swlValue: new FormControl<number | null>(null),
    swlUnit: new FormControl('', { nonNullable: true }), swlSource: new FormControl('', { nonNullable: true }),
    mblValue: new FormControl<number | null>(null), mblUnit: new FormControl('', { nonNullable: true }),
    mblSource: new FormControl('', { nonNullable: true }) });
  protected readonly personForm = new FormGroup({ personnelIdentifier: new FormControl('', { nonNullable: true, validators: Validators.required }),
    fullName: new FormControl('', { nonNullable: true, validators: Validators.required }),
    availabilityNote: new FormControl('', { nonNullable: true }), competency: new FormControl('', { nonNullable: true }),
    appUserId: new FormControl('', { nonNullable: true }), active: new FormControl(true, { nonNullable: true }) });
  protected readonly vendorForm = new FormGroup({ name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    contacts: new FormControl('', { nonNullable: true }), contractFrom: new FormControl('', { nonNullable: true }),
    contractTo: new FormControl('', { nonNullable: true }), active: new FormControl(true, { nonNullable: true }) });
  protected readonly certForm = new FormGroup({ typeCode: new FormControl('', { nonNullable: true, validators: Validators.required }),
    certificateNumber: new FormControl('', { nonNullable: true, validators: Validators.required }),
    issuer: new FormControl('', { nonNullable: true, validators: Validators.required }),
    issueDate: new FormControl('', { nonNullable: true, validators: Validators.required }),
    expiryDate: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  protected readonly inspectionForm = new FormGroup({ inspectedAt: new FormControl('', { nonNullable: true, validators: Validators.required }),
    result: new FormControl('', { nonNullable: true, validators: Validators.required }),
    source: new FormControl('', { nonNullable: true, validators: Validators.required }),
    nextDueAt: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  protected readonly policyForm = new FormGroup({ mode: new FormControl<'BLOCK' | 'WARN'>('BLOCK', { nonNullable: true }),
    warningDays: new FormControl<number | null>(null), requirementsConfigured: new FormControl(false, { nonNullable: true }) });
  protected readonly requirementForm = new FormGroup({ resourceKind: new FormControl<'ASSET' | 'PERSON'>('ASSET', { nonNullable: true }),
    scopeCode: new FormControl('', { nonNullable: true, validators: Validators.required }),
    certificateTypeCode: new FormControl('', { nonNullable: true, validators: Validators.required }) });

  constructor() {
    this.reload();
    this.modelSearch.valueChanges.pipe(debounceTime(250),switchMap(q => this.api.models({ page: 0, size: 100, q })),takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: v => this.models.set(v.items), error: e => this.fail(e) });
    // Cost heads and currencies come from the commercial masters; without that access the pickers stay empty.
    if (this.canWrite) {
      this.lookup.costHeads().pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: v => this.costHeads.set(v.filter(h => h.meta?.['kind'] !== 'REVENUE')), error: () => undefined });
      this.lookup.currencies().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.currencies.set(v), error: () => undefined });
    }
    this.route.data.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(data => this.mode.set((data['resourceMode'] as Mode) ?? 'equipment'));
  }

  protected reload(): void {
    this.tableParams.set({});
    this.api.codes('categories').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.categories.set(v), error: e => this.fail(e) });
    this.api.codes('competencies').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.competencies.set(v), error: e => this.fail(e) });
    this.api.codes('certificate-types').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.certificateTypes.set(v), error: e => this.fail(e) });
    this.api.models({ page: 0, size: 100 }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.models.set(v.items), error: e => this.fail(e) });
    this.modelTable()?.reload(); this.assetTable()?.reload(); this.personTable()?.reload();
    this.vendorTable()?.reload();
    this.api.policy().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => { this.policy.set(v); this.policyForm.patchValue(v); }, error: e => this.fail(e) });
    this.api.requirements().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.requirements.set(v), error: e => this.fail(e) });
  }

  protected editCode(kind: CodeKind, row: CodeRow): void {
    this.editing.set({ kind, id: row.code, version: row.version }); this.codeForm.setValue({ kind, code: row.code, label: row.label, active: row.active, costHeadId: row.costHeadId ?? '' });
  }
  protected saveCode(): void {
    if (this.codeForm.invalid) { this.codeForm.markAllAsTouched(); return; }
    const row = this.codeForm.getRawValue(); const editing = this.editing();
    this.send(this.api.saveCode(row.kind, { ...row, costHeadId: row.kind === 'categories' ? row.costHeadId || null : null }, editing?.kind === row.kind ? editing.version : undefined), () => {
      this.codeForm.reset({ kind: row.kind, code: '', label: '', active: true, costHeadId: '' }); this.editing.set(null); this.reload();
    },this.codeForm);
  }
  protected editModel(row: EquipmentModel): void {
    this.editing.set({ kind: 'model', id: row.id, version: row.version });
    this.modelForm.patchValue({ ...row, standardRateCurrency: row.standardRateCurrency ?? '' });
  }
  protected saveModel(): void {
    if (this.modelForm.invalid) { this.modelForm.markAllAsTouched(); return; }
    const v = this.modelForm.getRawValue(); const editing = this.editing();
    this.send(this.api.saveModel({ ...v,
      standardRate: v.standardRate ?? null, standardRateCurrency: v.standardRate == null ? null : v.standardRateCurrency || null }, editing?.kind === 'model' ? editing.id : undefined, editing?.version), () => {
      this.modelForm.reset({ categoryCode: '', code: '', name: '', active: true, standardRate: null, standardRateCurrency: 'USD' }); this.editing.set(null); this.reload();
    },this.modelForm);
  }
  protected editAsset(row: Asset): void {
    this.editing.set({ kind: 'asset', id: row.id, version: row.version }); this.assetForm.patchValue({ ...row,
      dimensionsText: row.dimensionsText ?? '', homeLocation: row.homeLocation ?? '',
      swlUnit: row.swlUnit ?? '', swlSource: row.swlSource ?? '', mblUnit: row.mblUnit ?? '', mblSource: row.mblSource ?? '' });
    this.select('assets', row.id);
    if (!this.models().some(model => model.id===row.modelId)) this.api.model(row.modelId)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: model => this.models.update(rows => [...rows,model]), error: e => this.fail(e) });
  }
  protected saveAsset(): void {
    if (this.assetForm.invalid) { this.assetForm.markAllAsTouched(); return; }
    const v = this.assetForm.getRawValue(), editing = this.editing();
    this.send(this.api.saveAsset({ ...v, branchId: null,
      dimensionsText: v.dimensionsText || null, homeLocation: v.homeLocation || null,
      swlUnit: v.swlUnit || null, swlSource: v.swlSource || null, mblUnit: v.mblUnit || null, mblSource: v.mblSource || null },
    editing?.kind === 'asset' ? editing.id : undefined, editing?.version), () => {
      this.assetForm.reset({ modelId: '', assetIdentifier: '', dimensionsText: '', status: 'AVAILABLE', homeLocation: '',
        swlValue: null, swlUnit: '', swlSource: '', mblValue: null, mblUnit: '', mblSource: '' }); this.editing.set(null); this.reload();
    },this.assetForm);
  }
  protected editPerson(row: Person): void {
    this.editing.set({ kind: 'person', id: row.id, version: row.version });
    this.personForm.patchValue({ ...row, appUserId: row.appUserId ?? '', availabilityNote: row.availabilityNote ?? '',
      competency: row.competencies.join(', ') }); this.select('personnel', row.id);
  }
  protected savePerson(): void {
    if (this.personForm.invalid) { this.personForm.markAllAsTouched(); return; }
    const v = this.personForm.getRawValue(), editing = this.editing();
    const { competency, ...person } = v;
    this.send(this.api.savePerson({ ...person, appUserId: v.appUserId || null,
      availabilityNote: v.availabilityNote || null,
      competencies: competency.split(',').map(code => code.trim()).filter(Boolean) },
    editing?.kind === 'person' ? editing.id : undefined, editing?.version), () => {
      this.personForm.reset({ personnelIdentifier: '', fullName: '', availabilityNote: '', competency: '', appUserId: '', active: true });
      this.editing.set(null); this.reload();
    },this.personForm);
  }
  protected editVendor(row: Vendor): void {
    this.editing.set({ kind: 'vendor', id: row.id, version: row.version }); this.vendorForm.patchValue({ ...row,
      contacts: row.contacts ?? '', contractFrom: row.contractFrom ?? '', contractTo: row.contractTo ?? '' });
  }
  protected saveVendor(): void {
    if (this.vendorForm.invalid) { this.vendorForm.markAllAsTouched(); return; }
    const v = this.vendorForm.getRawValue(), editing = this.editing();
    this.send(this.api.saveVendor({ ...v, contacts: v.contacts || null,
      contractFrom: v.contractFrom || null, contractTo: v.contractTo || null },
    editing?.kind === 'vendor' ? editing.id : undefined, editing?.version), () => {
      this.vendorForm.reset({ name: '', contacts: '', contractFrom: '', contractTo: '', active: true }); this.editing.set(null); this.reload();
    },this.vendorForm);
  }
  protected select(kind: 'assets' | 'personnel', id: string): void {
    this.selected.set({ kind, id }); this.editingCertificate.set(null); this.editingInspection.set(null); this.changeHistory.set([]);
    this.api.certificates(kind, id).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: v => this.certificates.set(v), error: e => this.fail(e) });
    if (kind === 'assets') this.api.inspections(id).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: v => this.inspections.set(v), error: e => this.fail(e) });
    else this.inspections.set([]);
  }
  protected setFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    const problem = file && uploadProblem(file);
    this.errorKey.set(problem);
    this.file.set(problem ? null : file);
    if (problem) input.value = '';
  }
  protected editCertificate(row: Certificate): void {
    this.editingCertificate.set(row);
    this.certForm.patchValue({ typeCode: row.typeCode, certificateNumber: row.certificateNumber, issuer: row.issuer,
      issueDate: row.issueDate, expiryDate: row.expiryDate ?? '', reason: '' });
    const owner=this.selected(); if(owner) this.api.certificateHistory(owner.kind,owner.id,row.id)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.changeHistory.set(v), error: e => this.fail(e) });
  }
  protected saveCertificate(): void {
    const owner = this.selected(); if (!owner || this.certForm.invalid) { this.certForm.markAllAsTouched(); return; }
    const v = this.certForm.getRawValue();
    if (v.expiryDate && v.expiryDate < v.issueDate) { this.errorKey.set('resources.invalidCertificateDates'); return; }
    const editing=this.editingCertificate();
    const request=editing ? this.api.editCertificate(owner.kind,owner.id,editing,{ ...v, expiryDate: v.expiryDate || null },this.file()) :
      this.api.addCertificate(owner.kind, owner.id, { ...v, expiryDate: v.expiryDate || null }, this.file());
    this.send(request, () => {
      this.certForm.reset({ typeCode: '', certificateNumber: '', issuer: '', issueDate: '', expiryDate: '', reason: '' });
      this.file.set(null); this.select(owner.kind, owner.id);
    },this.certForm);
  }
  protected editInspection(row: Inspection): void {
    this.editingInspection.set(row);
    this.inspectionForm.patchValue({ inspectedAt: row.inspectedAt, result: row.result, source: row.source,
      nextDueAt: row.nextDueAt ?? '', reason: '' });
    const owner=this.selected(); if(owner?.kind==='assets') this.api.inspectionHistory(owner.id,row.id)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: v => this.changeHistory.set(v), error: e => this.fail(e) });
  }
  protected saveInspection(): void {
    const owner = this.selected(); if (!owner || owner.kind !== 'assets' || this.inspectionForm.invalid) return;
    const v = this.inspectionForm.getRawValue();
    const editing=this.editingInspection();
    const request=editing ? this.api.editInspection(owner.id,editing,{ ...v, nextDueAt: v.nextDueAt || null }) :
      this.api.addInspection(owner.id, { ...v, nextDueAt: v.nextDueAt || null });
    this.send(request, () => {
      this.inspectionForm.reset({ inspectedAt: '', result: '', source: '', nextDueAt: '', reason: '' }); this.select(owner.kind, owner.id);
    },this.inspectionForm);
  }
  protected savePolicy(): void {
    const row = this.policy(); if (!row) return;
    this.send(this.api.savePolicy({ ...row, ...this.policyForm.getRawValue() }), () => this.reload(),this.policyForm);
  }
  protected saveRequirement(): void {
    if (this.requirementForm.invalid) { this.requirementForm.markAllAsTouched(); return; }
    this.send(this.api.addRequirement({ ...this.requirementForm.getRawValue(), active: true }), () => {
      this.requirementForm.reset({ resourceKind: 'ASSET', scopeCode: '', certificateTypeCode: '' }); this.reload();
    },this.requirementForm);
  }

  private send<T>(request: import('rxjs').Observable<T>, done: () => void, form?: FormGroup): void {
    this.busy.set(true); this.errorKey.set(null); this.saved.set(false);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); this.saved.set(true); done(); },
      error: error => { this.busy.set(false); const problem=toProblem(error);
        if (form) applyServerErrors(form,form===this.personForm ? { ...problem,
          errors: problem.errors?.map(item => ({ ...item, field: item.field==='competencies' ? 'competency' : item.field })) } : problem);
        this.errorKey.set(problemKey(problem)); },
    });
  }
  private fail(error: unknown): void { this.errorKey.set(problemKey(toProblem(error))); }
}
