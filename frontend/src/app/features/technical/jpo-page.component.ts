import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, FormRecord, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { DatePicker } from 'primeng/datepicker';
import { Dialog } from 'primeng/dialog';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { Observable, Subscription, catchError, of, switchMap } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { LanguageService } from '../../core/i18n/language.service';
import { DateTimePickerComponent } from '../../shared/components/date-time-picker/date-time-picker.component';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { JobDocument, JobTechnicalService } from './job-technical.service';
import { JpoContent, JpoOverview, JpoParty, JpoRevision, JpoService } from './jpo.service';

type OpenDialog = 'approve' | 'return' | 'issue' | 'accept' | 'view' | null;
type FieldKind = 'text' | 'area' | 'choice' | 'number' | 'document';

interface FieldDef { key: string; kind: FieldKind; wide?: boolean }

interface SectionDef {
  id: string;
  /** Key in the revision's `sources` map when the section is entered here until its own module exists. */
  source?: string;
  fields: FieldDef[];
}

const CHOICES: Record<string, string[]> = {
  shipHandling: ['HELM_ORDERS', 'COURSE_ORDERS'],
  handedness: ['LEFT', 'RIGHT'],
  constantHeadingPropulsion: ['CPP', 'TELEGRAPH'],
  manoeuvringPropulsion: ['CPP', 'TELEGRAPH'],
  constantHeadingDpMode: ['NOT_DP', 'DP', 'SEAGOING'],
  manoeuvringDpMode: ['NOT_DP', 'DP', 'SEAGOING'],
  manoeuvringDeadSlowCondition: ['BALLAST', 'LADEN'],
  approach: ['AT_ANCHOR', 'UNDERWAY', 'WITH_TUGS'],
  securityLevel: ['LEVEL_1', 'LEVEL_2', 'LEVEL_3'],
};

/** The JPO workbook's pages as editable sections; list sections (limits, rates, equipment) are drawn separately. */
const SECTIONS: SectionDef[] = [
  { id: 'operation', fields: [{ key: 'purpose', kind: 'area', wide: true }] },
  { id: 'mooring', source: 'mooringDiagram', fields: [
    { key: 'mooringDiagramDocumentId', kind: 'document', wide: true }, { key: 'mooringNotes', kind: 'area', wide: true }] },
  { id: 'manoeuvring', fields: [
    { key: 'constantHeadingCourseSpeed', kind: 'text' }, { key: 'manoeuvringCourseSpeed', kind: 'text' },
    { key: 'constantHeadingPropulsion', kind: 'choice' }, { key: 'manoeuvringPropulsion', kind: 'choice' },
    { key: 'constantHeadingDpMode', kind: 'choice' }, { key: 'manoeuvringDpMode', kind: 'choice' },
    { key: 'constantHeadingDeadSlow', kind: 'text' }, { key: 'manoeuvringDeadSlowKn', kind: 'number' },
    { key: 'manoeuvringDeadSlowCondition', kind: 'choice' }, { key: 'shipHandling', kind: 'choice' },
    { key: 'rudderType', kind: 'text' }, { key: 'maxRudderAngleDeg', kind: 'number' },
    { key: 'handedness', kind: 'choice' }, { key: 'speedMonitoring', kind: 'area', wide: true }] },
  { id: 'approach', fields: [
    { key: 'rendezvous', kind: 'text' }, { key: 'transferLocation', kind: 'text' }, { key: 'approach', kind: 'choice' },
    { key: 'approachNotes', kind: 'area', wide: true },
    { key: 'additionalRequirements', kind: 'area', wide: true }, { key: 'mooringRopeFailure', kind: 'area', wide: true }] },
  { id: 'security', fields: [
    { key: 'securityLevel', kind: 'choice' }, { key: 'contingency', kind: 'area', wide: true }, { key: 'personnelTransfer', kind: 'area', wide: true }] },
  { id: 'unmooring', fields: [
    { key: 'oilBoom', kind: 'area', wide: true }, { key: 'unmooringDiagramDocumentId', kind: 'document', wide: true },
    { key: 'unmooringNotes', kind: 'area', wide: true }] },
];

const SCALAR_KEYS = SECTIONS.flatMap((s) => s.fields.map((f) => f.key));
const PARTIES: JpoParty[] = ['DISCHARGING_MASTER', 'RECEIVING_MASTER', 'STS_SUPERINTENDENT'];

type LimitGroup = FormGroup<{ parameter: FormControl<string>; limit: FormControl<string>; unit: FormControl<string | null>; action: FormControl<string> }>;
type RateGroup = FormGroup<{ cargoGradeCode: FormControl<string>; maxRatePerLine: FormControl<string | null>; rateUnit: FormControl<string | null> }>;
type EquipmentGroup = FormGroup<{ category: FormControl<string>; number: FormControl<number | null>; dimensions: FormControl<string | null>; strings: FormControl<number | null> }>;
type AncillaryGroup = FormGroup<{ item: FormControl<string>; value: FormControl<string | null> }>;

/**
 * Joint Plan of Operation of a job (FR-TECH-02): revisions prepared from the job's data plus structured entries,
 * submitted when nothing blocks them, approved by a second person, issued as an immutable PDF and accepted by the
 * masters and the STS Superintendent. Sections whose module is not built yet are entered here and marked as such.
 */
@Component({
  selector: 's2s-jpo-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Checkbox, DatePicker, Dialog, InputNumber, InputText, Message, Select, Tag,
    Textarea, DateTimePickerComponent, DocumentViewerComponent, EmptyStateComponent, FormFieldComponent, PageHeaderComponent,
    ReasonDialogComponent, StatusTagComponent, UtcDateTimePipe,
  ],
  templateUrl: './jpo-page.component.html',
  styleUrl: './jpo-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JpoPageComponent {
  private readonly service = inject(JpoService);
  private readonly technical = inject(JobTechnicalService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private loadedJobId = '';
  private jobVersion = 0;
  private overviewRequest?: Subscription;
  private documentsRequest?: Subscription;
  private revisionRequest?: Subscription;

  /** Job id (route parameter). */
  readonly jobId = input.required<string>();

  protected readonly sections = SECTIONS;
  protected readonly overview = signal<JpoOverview | null>(null);
  protected readonly revision = signal<JpoRevision | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly documents = signal<JobDocument[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  protected readonly viewing = signal<ViewableFile | null>(null);
  /** Bumped on every form change, so `dirty` is re-read by the template. */
  private readonly changes = signal(0);

  protected readonly can = computed(() => new Set(this.revision()?.allowedActions ?? []));
  protected readonly editable = computed(() => this.can().has('EDIT'));
  protected readonly dirty = computed(() => (this.changes(), this.form.dirty));
  protected readonly canManageAnnex = computed(() => this.auth.hasPermission(Permissions.masterTechnicalRead));
  protected readonly canReadWeather = computed(() => this.auth.hasPermission(Permissions.techWeatherRead));
  protected readonly canReadResources = computed(() => this.auth.hasPermission(Permissions.opsAllocationRead));
  protected hasAllocatedEquipment(category: string): boolean {
    return !!this.revision()?.snapshot.allocatedResources?.some(asset => asset.kind === 'ASSET' && asset.category === category);
  }

  protected readonly form = new FormGroup({
    operationDate: new FormControl<Date | null>(null),
    reverseLightering: new FormControl(false, { nonNullable: true }),
    similarLoaBerthing: new FormControl(false, { nonNullable: true }),
    fields: new FormRecord<FormControl<string | null>>(
      Object.fromEntries(SCALAR_KEYS.map((key) => [key, new FormControl<string | null>(null, Validators.maxLength(2000))]))),
    operatingLimits: new FormArray<LimitGroup>([]),
    cargoRates: new FormArray<RateGroup>([]),
    equipment: new FormArray<EquipmentGroup>([]),
    ancillary: new FormArray<AncillaryGroup>([]),
  });

  protected readonly acceptForm = new FormGroup({
    party: new FormControl<JpoParty | null>(null, Validators.required),
    personName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    acceptedAt: new FormControl<Date | null>(null, Validators.required),
    signedDocumentId: new FormControl<string | null>(null),
  });

  /** Options per choice field, translated. */
  protected readonly choiceOptions = computed(() => {
    this.language.current();
    return Object.fromEntries(Object.entries(CHOICES).map(([key, codes]) =>
      [key, codes.map((value) => ({ value, label: this.translate.instant(`jpo.choice.${value}`) }))]));
  });

  protected readonly documentOptions = computed(() => {
    const vessels = this.revision()?.snapshot.vessels ?? [];
    return this.documents().map((d) => {
      const vessel = vessels.find((v) => v.jobVesselId === d.jobVesselId);
      return { value: d.id, label: vessel?.name ? `${d.fileName} · ${vessel.name}` : d.fileName };
    });
  });

  protected readonly partyOptions = computed(() => {
    this.language.current();
    const done = new Set(this.revision()?.acceptances.map((a) => a.party) ?? []);
    return PARTIES.filter((p) => !done.has(p)).map((value) => ({ value, label: this.translate.instant(`jpo.party.${value}`) }));
  });

  /** Verified voyage values as rows: one per field, a column per vessel. */
  protected readonly voyageRows = computed(() => {
    const snapshot = this.revision()?.snapshot;
    if (!snapshot) return [];
    const fields = [...new Map(snapshot.voyage.map((v) => [v.fieldCode, v.label])).entries()];
    return fields.map(([code, label]) => ({
      label,
      values: snapshot.vessels.map((vessel) => {
        const value = snapshot.voyage.find((v) => v.role === vessel.role && v.fieldCode === code);
        return value ? `${value.value} ${value.unit}` : '—';
      }),
    }));
  });

  constructor() {
    effect(() => {
      const id = this.jobId();
      untracked(() => this.load(id));
    });
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
  }

  protected field(key: string): FormControl<string | null> {
    return this.form.controls.fields.controls[key];
  }

  protected source(section: string): string | null {
    return this.revision()?.sources[section] ?? null;
  }

  protected roleLabel(role: string | null): string {
    return this.translate.instant(role ? `jpo.role.${role}` : 'jpo.role.BOTH');
  }

  protected blockerKey(code: string): string {
    return `jpo.blocker.${code.replace('.', '_')}`;
  }

  protected select(id: string): void {
    if (id === this.selectedId()) return;
    if (this.saving() || (this.dirty() && !window.confirm(this.translate.instant('jpo.discardDraft')))) return;
    this.selectedId.set(id);
    this.revision.set(null);
    this.form.markAsPristine();
    this.changes.update(n => n + 1);
    this.loadRevision(id);
  }

  protected pdfUrl(revision: JpoRevision): string {
    return this.service.pdfUrl(this.jobId(), revision);
  }

  protected xlsxUrl(revision: JpoRevision): string {
    return this.service.xlsxUrl(this.jobId(), revision);
  }

  protected documentName(id: string | null): string {
    return this.documents().find((d) => d.id === id)?.fileName ?? '—';
  }

  protected view(id: string | null): void {
    const document = this.documents().find((d) => d.id === id);
    if (!document) return;
    this.viewing.set({ id: document.fileId, name: document.fileName, contentType: document.contentType });
    this.dialog.set('view');
  }

  // ---------- list rows ----------

  protected addLimit(): void {
    this.form.controls.operatingLimits.push(limitGroup({ parameter: '', limit: '', unit: null, action: '' }));
    this.form.markAsDirty();
  }

  protected addAncillary(): void {
    this.form.controls.ancillary.push(ancillaryGroup({ item: '', value: null }));
    this.form.markAsDirty();
  }

  protected removeRow(array: FormArray, index: number): void {
    array.removeAt(index);
    this.form.markAsDirty();
  }

  // ---------- actions ----------

  protected create(): void {
    this.run(this.service.create(this.jobId()), undefined, (revision) => this.reloadOverview(revision.id));
  }

  protected save(): void {
    const revision = this.revision();
    if (!revision || this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const jobId = this.jobId();
    // Once nothing is missing, saving also marks the plan done and creates the final PDF.
    const saved = this.service.save(jobId, revision, this.content(revision)).pipe(switchMap((current) =>
      current.blockers.length === 0 && current.allowedActions.includes('SUBMIT') ? this.service.submit(jobId, current) : of(current)));
    this.run(saved, this.form, (current) => { if (current.status !== 'DRAFT') this.reloadOverview(); });
  }

  protected submit(): void {
    const revision = this.revision();
    if (!revision || this.saving()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const jobId = this.jobId();
    const saved = this.dirty() ? this.service.save(jobId, revision, this.content(revision)) : of(revision);
    this.run(saved.pipe(switchMap((current) => this.service.submit(jobId, current))), this.form,
      () => this.reloadOverview());
  }

  protected decide(decision: 'APPROVE' | 'RETURN', reason: string | null): void {
    const revision = this.revision();
    if (revision) this.run(this.service.decide(this.jobId(), revision, decision, reason), undefined, () => this.reloadOverview());
  }

  protected issue(): void {
    const revision = this.revision();
    if (revision) this.run(this.service.issue(this.jobId(), revision), undefined, () => this.reloadOverview());
  }

  protected openAccept(): void {
    this.dialogError.set(null);
    this.acceptForm.reset({ party: this.partyOptions()[0]?.value ?? null, personName: '', acceptedAt: new Date(), signedDocumentId: null });
    this.dialog.set('accept');
  }

  protected saveAccept(): void {
    const revision = this.revision();
    if (!revision || this.acceptForm.invalid || this.saving()) {
      this.acceptForm.markAllAsTouched();
      return;
    }
    const v = this.acceptForm.getRawValue();
    this.run(this.service.accept(this.jobId(), revision, {
      party: v.party!, personName: v.personName.trim(), acceptedAt: v.acceptedAt!.toISOString(), signedDocumentId: v.signedDocumentId,
    }), this.acceptForm);
  }

  protected closeDialog(open: boolean): void {
    if (!open) {
      this.dialog.set(null);
      this.viewing.set(null);
    }
  }

  protected reload(): void {
    this.load(this.jobId());
  }

  // ---------- loading ----------

  private load(jobId: string): void {
    if (jobId !== this.loadedJobId) {
      this.loadedJobId = jobId;
      this.jobVersion++;
      this.revisionRequest?.unsubscribe();
      this.overview.set(null);
      this.revision.set(null);
      this.selectedId.set(null);
      this.documents.set([]);
      this.dialog.set(null);
      this.saving.set(false);
    }
    this.loading.set(true);
    this.errorKey.set(null);
    this.reloadOverview();
    this.documentsRequest?.unsubscribe();
    this.documentsRequest = this.technical.documents(jobId).pipe(catchError(() => of([] as JobDocument[])), takeUntilDestroyed(this.destroyRef))
      .subscribe((list) => this.documents.set(list));
  }

  private reloadOverview(select?: string): void {
    this.overviewRequest?.unsubscribe();
    this.overviewRequest = this.service.overview(this.jobId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => {
        this.overview.set(overview);
        const id = select ?? (overview.revisions.some((r) => r.id === this.selectedId()) ? this.selectedId() : overview.revisions[0]?.id ?? null);
        this.selectedId.set(id);
        if (id && id !== this.revision()?.id) this.loadRevision(id);
        if (!id) this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  private loadRevision(id: string): void {
    this.revisionRequest?.unsubscribe();
    this.loading.set(true);
    this.revisionRequest = this.service.revision(this.jobId(), id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (revision) => {
        this.loading.set(false);
        this.show(revision);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  /** Shows a revision and resets the form to its saved entries. */
  private show(revision: JpoRevision): void {
    this.revision.set(revision);
    const c = revision.content;
    const f = this.form.controls;
    f.operatingLimits.clear({ emitEvent: false });
    c.operatingLimits.forEach((row) => f.operatingLimits.push(limitGroup(row), { emitEvent: false }));
    f.cargoRates.clear({ emitEvent: false });
    c.cargoRates.forEach((row) => f.cargoRates.push(rateGroup(row), { emitEvent: false }));
    f.equipment.clear({ emitEvent: false });
    c.equipment.forEach((row) => f.equipment.push(equipmentGroup(row), { emitEvent: false }));
    f.ancillary.clear({ emitEvent: false });
    c.ancillary.forEach((row) => f.ancillary.push(ancillaryGroup(row), { emitEvent: false }));
    const fields = Object.fromEntries(SCALAR_KEYS.map((key) => {
      const value = (c as unknown as Record<string, unknown>)[key];
      return [key, value === null || value === undefined ? null : String(value)];
    }));
    this.form.reset({
      operationDate: c.operationDate ? fromDate(c.operationDate) : null,
      reverseLightering: c.reverseLightering,
      similarLoaBerthing: c.similarLoaBerthing,
      fields,
    });
    if (revision.allowedActions.includes('EDIT')) this.form.enable({ emitEvent: false });
    else this.form.disable({ emitEvent: false });
    this.changes.update((n) => n + 1);
  }

  private content(revision: JpoRevision): JpoContent {
    const v = this.form.getRawValue();
    const text = (key: string) => {
      const value = v.fields[key];
      return value === null || value === undefined || String(value).trim() === '' ? null : String(value).trim();
    };
    const scalars = Object.fromEntries(SCALAR_KEYS.map((key) => [key, text(key)]));
    return {
      ...revision.content,
      ...scalars,
      operationDate: v.operationDate ? toDate(v.operationDate) : null,
      reverseLightering: v.reverseLightering,
      similarLoaBerthing: v.similarLoaBerthing,
      operatingLimits: v.operatingLimits.map((r) => ({ parameter: r.parameter.trim(), limit: r.limit.trim(), unit: blank(r.unit), action: r.action.trim() })),
      cargoRates: v.cargoRates.map((r) => ({ cargoGradeCode: r.cargoGradeCode, maxRatePerLine: blank(r.maxRatePerLine), rateUnit: blank(r.rateUnit) })),
      equipment: v.equipment.map((r) => ({ category: r.category, number: r.number, dimensions: blank(r.dimensions), strings: r.strings })),
      ancillary: v.ancillary.map((r) => ({ item: r.item.trim(), value: blank(r.value) })),
    } as JpoContent;
  }

  /** Runs an action, shows the returned revision, and keeps a dialog open with its error if it fails. */
  private run(call: Observable<JpoRevision>, form?: FormGroup, after?: (result: JpoRevision) => void): void {
    const version = this.jobVersion;
    this.saving.set(true);
    this.errorKey.set(null);
    this.dialogError.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        if (version !== this.jobVersion) return;
        this.saving.set(false);
        this.dialog.set(null);
        this.selectedId.set(result.id);
        this.show(result);
        after?.(result);
      },
      error: (error: unknown) => { if (version === this.jobVersion) this.fail(toProblem(error), form); },
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    const key = problemKey(problem);
    if (form && applyServerErrors(form, problem)) {
      if (this.dialog()) this.dialogError.set('errors.codes.VALIDATION_FAILED');
      else this.errorKey.set('errors.codes.VALIDATION_FAILED');
    } else if (this.dialog() === 'accept') {
      this.dialogError.set(key);
    } else {
      this.dialog.set(null);
      this.errorKey.set(key);
    }
    // A blocked submission returns the open items; reload so the list is current.
    if (problem.code === 'JPO_INCOMPLETE' && this.revision()) this.loadRevision(this.revision()!.id);
  }
}

function blank(value: string | null | undefined): string | null {
  const text = value === null || value === undefined ? '' : String(value).trim();
  return text === '' ? null : text;
}

function limitGroup(row: { parameter: string; limit: string; unit: string | null; action: string }): LimitGroup {
  return new FormGroup({
    parameter: new FormControl(row.parameter, { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    limit: new FormControl(row.limit, { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    unit: new FormControl<string | null>(row.unit, Validators.maxLength(20)),
    action: new FormControl(row.action, { nonNullable: true, validators: [Validators.required, Validators.maxLength(300)] }),
  });
}

function rateGroup(row: { cargoGradeCode: string; maxRatePerLine: string | null; rateUnit: string | null }): RateGroup {
  return new FormGroup({
    cargoGradeCode: new FormControl(row.cargoGradeCode, { nonNullable: true }),
    maxRatePerLine: new FormControl<string | null>(row.maxRatePerLine === null ? null : String(row.maxRatePerLine), Validators.pattern(/^\d{1,7}(\.\d{1,2})?$/)),
    rateUnit: new FormControl<string | null>(row.rateUnit, Validators.maxLength(20)),
  });
}

function equipmentGroup(row: { category: string; number: number | null; dimensions: string | null; strings: number | null }): EquipmentGroup {
  return new FormGroup({
    category: new FormControl(row.category, { nonNullable: true }),
    number: new FormControl<number | null>(row.number, [Validators.min(0), Validators.max(999)]),
    dimensions: new FormControl<string | null>(row.dimensions, Validators.maxLength(200)),
    strings: new FormControl<number | null>(row.strings, [Validators.min(0), Validators.max(99)]),
  });
}

function ancillaryGroup(row: { item: string; value: string | null }): AncillaryGroup {
  return new FormGroup({
    item: new FormControl(row.item, { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    value: new FormControl<string | null>(row.value, Validators.maxLength(200)),
  });
}

/** `YYYY-MM-DD` of a date picker value, as picked (no time zone conversion). */
function toDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function fromDate(value: string): Date {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}
