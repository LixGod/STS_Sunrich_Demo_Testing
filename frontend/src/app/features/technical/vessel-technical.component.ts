import { HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { ProgressBar } from 'primeng/progressbar';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { catchError, of } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { LanguageService } from '../../core/i18n/language.service';
import { uploadProblem } from '../../core/files/upload-validation';
import { LookupService } from '../../core/lookups/lookup.service';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import {
  ParticularField, Particulars, ParticularsOverview, ParticularValueRequest, TechnicalService, VesselDocument, VesselRow,
} from './technical.service';

type ValueRow = FormGroup<{
  fieldCode: FormControl<string>;
  value: FormControl<string>;
  sourceDocumentId: FormControl<string | null>;
  sourcePage: FormControl<number | null>;
  confirmed: FormControl<boolean>;
}>;

const NUMBER = /^\d{1,9}(\.\d{1,3})?$/;
const ACCEPT = 'application/pdf,image/png,image/jpeg';

/**
 * Vessel technical page (FR-TECH-01, FR-DOC-01): the vessel's source documents, and its particulars entered by hand
 * beside the document they are read from (manual Q88 entry, K1). Each value cites document and page; a second person
 * confirms every value against its source and verifies the version. On phones the document stacks above the form.
 */
@Component({
  selector: 's2s-vessel-technical',
  imports: [
    FormsModule, ReactiveFormsModule, TranslatePipe, Button, Checkbox, InputNumber, InputText, Message, ProgressBar, Select, Textarea,
    DocumentViewerComponent, FormFieldComponent, PageHeaderComponent, ReasonDialogComponent, StatusTagComponent, UtcDateTimePipe,
  ],
  templateUrl: './vessel-technical.component.html',
  styleUrl: './vessel-technical.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VesselTechnicalComponent {
  private readonly service = inject(TechnicalService);
  private readonly lookups = inject(LookupService);
  private readonly auth = inject(AuthService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Vessel id (route parameter). */
  readonly id = input.required<string>();

  protected readonly accept = ACCEPT;
  protected readonly vessel = signal<VesselRow | null>(null);
  protected readonly documents = signal<VesselDocument[]>([]);
  protected readonly fields = signal<ParticularField[]>([]);
  protected readonly overview = signal<ParticularsOverview | null>(null);
  protected readonly current = signal<Particulars | null>(null);
  protected readonly viewing = signal<ViewableFile | null>(null);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly returning = signal(false);

  // Upload
  protected readonly uploadType = signal<string | null>(null);
  protected readonly uploadTitle = signal('');
  protected readonly uploadFile = signal<File | null>(null);
  protected readonly uploadProgress = signal<number | null>(null);
  protected readonly uploadError = signal<string | null>(null);

  protected readonly canUpload = computed(() => this.auth.hasPermission(Permissions.documentWrite));
  protected readonly showParticulars = computed(() => !this.auth.hasPermission(Permissions.masterCommercialWrite)
    || this.auth.hasPermission(Permissions.techVesselWrite));
  protected readonly can = computed(() => new Set(this.current()?.allowedActions ?? []));
  protected readonly editing = computed(() => this.can().has('EDIT'));
  protected readonly verifying = computed(() => this.can().has('VERIFY'));

  protected readonly documentTypes = toSignal(
    this.lookups.documentTypes('VESSEL').pipe(catchError(() => of([]))),
    { initialValue: [] },
  );

  protected readonly sourceOptions = computed(() => this.documents().map((d) => ({
    value: d.id,
    label: `${d.documentTypeName ?? d.documentTypeCode} v${d.versionNo}${d.title ? ' · ' + d.title : ''} · ${d.file?.name ?? ''}`,
  })));

  protected readonly versionOptions = computed(() => {
    this.language.current();
    return (this.overview()?.versions ?? []).map((v) => ({
      value: v.id,
      label: `${this.translate.instant('technical.versionLabel', { no: v.versionNo })} · ${this.translate.instant('status.' + v.status)}`,
    }));
  });

  protected readonly groups = computed(() => {
    const result: { group: string; fields: ParticularField[] }[] = [];
    for (const field of this.fields()) {
      let entry = result.find((g) => g.group === field.group);
      if (!entry) result.push((entry = { group: field.group, fields: [] }));
      entry.fields.push(field);
    }
    return result;
  });

  protected readonly form = new FormGroup({
    notes: new FormControl('', { nonNullable: true, validators: Validators.maxLength(1000) }),
    values: new FormArray<ValueRow>([]),
  });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: null });

  /** Verify is possible once every entered value is ticked as checked against its source. */
  protected readonly allConfirmed = computed(() => {
    this.formValue();
    const rows = this.form.controls.values.controls.filter((r) => r.controls.value.value.trim() !== '');
    return rows.length > 0 && rows.every((r) => r.controls.confirmed.value);
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    effect(() => {
      if (this.auth.hasPermission(Permissions.masterCommercialWrite) && !this.uploadType()) {
        const ga = this.documentTypes().find(type => type.meta?.['code'] === 'GA-PLAN');
        if (ga) this.uploadType.set(ga.value);
      }
    });
  }

  protected row(code: string): ValueRow {
    return this.form.controls.values.controls.find((r) => r.controls.fieldCode.value === code)!;
  }


  protected isWarned(code: string): boolean {
    return (this.current()?.warnings ?? []).some((w) => w.fields.includes(code));
  }

  protected view(document: VesselDocument | undefined): void {
    if (document?.file) this.viewing.set({ id: document.file.id, name: document.file.name, contentType: document.file.contentType });
  }

  protected viewSource(code: string): void {
    const id = this.row(code).controls.sourceDocumentId.value;
    this.view(this.documents().find((d) => d.id === id));
  }

  protected documentName(id: string | null): string {
    return this.sourceOptions().find((o) => o.value === id)?.label ?? '—';
  }

  protected pickFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.uploadError.set(null);
    const problem = file && uploadProblem(file);
    if (problem) {
      this.uploadError.set(problem);
      this.uploadFile.set(null);
      (event.target as HTMLInputElement).value = '';
      return;
    }
    this.uploadFile.set(file);
  }

  protected uploadNow(input: HTMLInputElement): void {
    const file = this.uploadFile();
    const type = this.uploadType();
    if (!file || !type) return;
    this.uploadProgress.set(0);
    this.uploadError.set(null);
    this.service.upload(this.id(), type, this.uploadTitle().trim() || null, file).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) this.uploadProgress.set(Math.round((100 * event.loaded) / event.total));
        if (event.type === HttpEventType.Response && event.body) {
          this.uploadProgress.set(null);
          this.uploadFile.set(null);
          this.uploadTitle.set('');
          input.value = '';
          this.documents.update((list) => [event.body!, ...list.map((d) =>
            d.documentTypeId === event.body!.documentTypeId ? { ...d, latest: false } : d)]);
          this.view(event.body);
          this.messages.add({ severity: 'success', summary: this.translate.instant('documents.uploaded'), detail: event.body.file?.name });
        }
      },
      error: (error: unknown) => {
        this.uploadProgress.set(null);
        this.uploadError.set(problemKey(toProblem(error)));
      },
    });
  }

  protected selectVersion(id: string): void {
    if (id && id !== this.current()?.id) this.open(id);
  }

  protected newVersion(): void {
    this.run(this.service.newVersion(this.id()), true);
  }

  protected save(): void {
    const current = this.current();
    if (!current) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.errorKey.set('errors.codes.VALIDATION_FAILED');
      return;
    }
    this.run(this.service.save(current, this.form.controls.notes.value.trim() || null, this.requestValues()));
  }

  protected submit(): void {
    const current = this.current();
    if (!current) return;
    // Save what is on screen first, so the submitted version is what the user sees.
    if (this.form.dirty) {
      if (this.form.invalid) {
        this.form.markAllAsTouched();
        this.errorKey.set('errors.codes.VALIDATION_FAILED');
        return;
      }
      this.saving.set(true);
      this.service.save(current, this.form.controls.notes.value.trim() || null, this.requestValues())
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (saved) => this.run(this.service.submit(saved)), error: (e: unknown) => this.fail(toProblem(e)) });
      return;
    }
    this.run(this.service.submit(current));
  }

  protected verify(): void {
    const current = this.current();
    if (!current) return;
    const confirmed = this.form.controls.values.controls.filter((r) => r.controls.confirmed.value).map((r) => r.controls.fieldCode.value);
    this.run(this.service.verify(current, confirmed));
  }

  protected confirmReturn(reason: string | null): void {
    const current = this.current();
    if (!current || !reason) return;
    this.returning.set(false);
    this.run(this.service.returnForCorrection(current, reason));
  }

  protected closeReturn(open: boolean): void {
    if (!open) this.returning.set(false);
  }

  protected reload(): void {
    this.load(this.id());
  }

  private load(vesselId: string): void {
    this.errorKey.set(null);
    this.service.vessel(vesselId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (v) => this.vessel.set(v),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
    this.service.documents(vesselId).pipe(catchError(() => of([] as VesselDocument[])), takeUntilDestroyed(this.destroyRef))
      .subscribe((list) => this.documents.set(list));
    if (this.showParticulars()) this.service.fields().pipe(catchError(() => of([] as ParticularField[])), takeUntilDestroyed(this.destroyRef)).subscribe((fields) => {
      this.fields.set(fields);
      this.refreshOverview(true);
    });
  }

  private refreshOverview(openLatest: boolean): void {
    this.service.overview(this.id()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => {
        this.overview.set(overview);
        if (openLatest && overview.versions.length) this.open(overview.versions[0].id);
      },
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private open(id: string): void {
    this.service.particulars(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (p) => this.show(p),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private run(call: ReturnType<TechnicalService['submit']>, refreshVessel = false): void {
    this.saving.set(true);
    this.errorKey.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.show(result);
        this.refreshOverview(false);
        if (refreshVessel || result.status === 'VERIFIED') {
          this.service.vessel(this.id()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe((v) => this.vessel.set(v));
        }
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
      },
      error: (error: unknown) => this.fail(toProblem(error)),
    });
  }

  private show(particulars: Particulars): void {
    this.current.set(particulars);
    const rows = this.form.controls.values;
    rows.clear({ emitEvent: false });
    for (const field of this.fields()) {
      const value = particulars.values.find((v) => v.fieldCode === field.code);
      rows.push(new FormGroup({
        fieldCode: new FormControl(field.code, { nonNullable: true }),
        value: new FormControl(value?.value ?? '', {
          nonNullable: true,
          validators: [Validators.maxLength(100), ...(field.dataType === 'NUMBER' ? [Validators.pattern(NUMBER)] : [])],
        }),
        sourceDocumentId: new FormControl<string | null>(value?.sourceDocumentId ?? null),
        sourcePage: new FormControl<number | null>(value?.sourcePage ?? null, [Validators.min(1), Validators.max(9999)]),
        confirmed: new FormControl(value?.verified ?? false, { nonNullable: true }),
      }), { emitEvent: false });
    }
    this.form.controls.notes.setValue(particulars.notes ?? '', { emitEvent: false });
    if (this.editing()) {
      this.form.enable({ emitEvent: false });
      rows.controls.forEach((r) => r.controls.confirmed.disable({ emitEvent: false }));
    } else {
      this.form.disable({ emitEvent: false });
      // The reviewer ticks each value as checked against its source.
      if (this.verifying()) rows.controls.forEach((r) => r.controls.confirmed.enable({ emitEvent: false }));
    }
    this.form.markAsPristine();
    this.form.updateValueAndValidity();
    // Show the first cited source, if any.
    if (!this.viewing()) {
      const firstSource = particulars.values.find((v) => v.sourceDocumentId)?.sourceDocumentId;
      this.view(this.documents().find((d) => d.id === firstSource) ?? this.documents()[0]);
    }
  }

  private requestValues(): ParticularValueRequest[] {
    return this.form.controls.values.controls
      .filter((r) => r.controls.value.value.trim() !== '')
      .map((r) => ({
        fieldCode: r.controls.fieldCode.value,
        value: r.controls.value.value.trim(),
        sourceDocumentId: r.controls.sourceDocumentId.value,
        sourcePage: r.controls.sourcePage.value,
      }));
  }

  private fail(problem: Problem): void {
    this.saving.set(false);
    this.errorKey.set(problemKey(problem));
    // Field errors arrive as `values.<CODE>` or `values.<CODE>.source`: mark the matching row.
    for (const error of problem.errors ?? []) {
      const match = /^values\.([A-Z_]+)(\.source)?$/.exec(error.field);
      if (!match) continue;
      const row = this.form.controls.values.controls.find((r) => r.controls.fieldCode.value === match[1]);
      const control = error.code === 'sourceRequired' || match[2] ? row?.controls.sourceDocumentId : row?.controls.value;
      control?.setErrors({ server: error.message ?? error.code });
      control?.markAsTouched();
    }
  }
}

