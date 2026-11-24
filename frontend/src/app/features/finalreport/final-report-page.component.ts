import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { MultiSelect } from 'primeng/multiselect';
import { Select } from 'primeng/select';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { Observable, catchError, of } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { FinalReportService, JobDocumentRef, ReportContent, ReportOverview, ReportRevision } from './final-report.service';

type OpenDialog = 'approve' | 'return' | 'issue' | null;
type EquipmentGroup = FormGroup<{ category: FormControl<'FENDER' | 'HOSE' | 'OTHER'>; description: FormControl<string>; quantity: FormControl<number | null> }>;
type AttendanceGroup = FormGroup<{ capacity: FormControl<string>; name: FormControl<string> }>;

/**
 * Final operation report of a job (FR-REP-01): a draft generated from the job's records, edited, submitted when
 * complete, approved by someone else and issued as a fixed PDF. Job records are frozen at submission.
 */
@Component({
  selector: 's2s-final-report-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Dialog, InputText, Message, MultiSelect, Select, Tag, Textarea, EmptyStateComponent,
    FormFieldComponent, PageHeaderComponent, ReasonDialogComponent, StatusTagComponent, UtcDateTimePipe,
  ],
  templateUrl: './final-report-page.component.html',
  styleUrl: './final-report-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FinalReportPageComponent {
  private readonly service = inject(FinalReportService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Job id (route parameter). */
  readonly jobId = input.required<string>();

  protected readonly overview = signal<ReportOverview | null>(null);
  protected readonly revision = signal<ReportRevision | null>(null);
  protected readonly documents = signal<JobDocumentRef[]>([]);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  private readonly changes = signal(0);

  protected readonly can = computed(() => new Set(this.revision()?.allowedActions ?? []));
  protected readonly editable = computed(() => this.can().has('EDIT'));
  protected readonly dirty = computed(() => (this.changes(), this.form.dirty));
  protected readonly zone = computed(() => this.revision()?.snapshot.timezone ?? 'UTC');
  protected readonly documentOptions = computed(() => this.documents().map((d) => ({ value: d.id, label: d.fileName })));
  protected readonly categoryOptions = computed(() => {
    this.language.current();
    return ['FENDER', 'HOSE', 'OTHER'].map((value) => ({ value, label: this.translate.instant(`finalReport.category.${value}`) }));
  });

  protected readonly form = new FormGroup({
    vhfChannel: new FormControl('', { nonNullable: true, validators: Validators.maxLength(50) }),
    summary: new FormControl('', { nonNullable: true, validators: Validators.maxLength(8000) }),
    remarks: new FormControl('', { nonNullable: true, validators: Validators.maxLength(8000) }),
    equipment: new FormArray<EquipmentGroup>([]),
    attendance: new FormArray<AttendanceGroup>([]),
    attachmentDocumentIds: new FormControl<string[]>([], { nonNullable: true }),
  });

  constructor() {
    effect(() => {
      const id = this.jobId();
      untracked(() => this.load(id));
    });
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
  }

  protected select(id: string): void {
    this.service.revision(this.jobId(), id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (r) => this.show(r),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  protected create(): void {
    this.run(this.service.create(this.jobId()));
  }

  protected addEquipment(): void {
    this.form.controls.equipment.push(equipment({ category: 'FENDER', description: '', quantity: null }));
    this.form.markAsDirty();
  }

  protected addAttendance(): void {
    this.form.controls.attendance.push(attendance({ capacity: '', name: '' }));
    this.form.markAsDirty();
  }

  protected remove(array: FormArray, index: number): void {
    array.removeAt(index);
    this.form.markAsDirty();
  }

  protected save(): void {
    const r = this.revision();
    if (!r || this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const content: ReportContent = {
      vhfChannel: v.vhfChannel.trim() || null, summary: v.summary.trim() || null, remarks: v.remarks.trim() || null,
      equipment: v.equipment.map((e) => ({ category: e.category, description: e.description.trim(), quantity: e.quantity })),
      attendance: v.attendance.map((a) => ({ capacity: a.capacity.trim(), name: a.name.trim() })),
      attachmentDocumentIds: v.attachmentDocumentIds,
    };
    this.run(this.service.save(this.jobId(), r, content), this.form);
  }

  protected act(action: 'submit' | 'issue' | 'regenerate-summary'): void {
    const r = this.revision();
    if (r) this.run(this.service.action(this.jobId(), r, action));
  }

  protected decide(decision: 'APPROVE' | 'RETURN', reason: string | null): void {
    const r = this.revision();
    if (r) this.run(this.service.decide(this.jobId(), r, decision, reason));
  }

  protected pdfUrl(r: ReportRevision): string {
    return this.service.pdfUrl(this.jobId(), r);
  }

  protected closeDialog(open: boolean): void {
    if (!open) this.dialog.set(null);
  }

  protected reload(): void {
    this.load(this.jobId());
  }

  private load(jobId: string, select?: string): void {
    this.errorKey.set(null);
    this.service.overview(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (o) => {
        this.overview.set(o);
        const id = select ?? (o.revisions.some((r) => r.id === this.revision()?.id) ? this.revision()!.id : o.revisions[0]?.id);
        if (id) this.select(id);
      },
      error: () => this.errorKey.set('errors.loadFailed'),
    });
    this.service.documents(jobId).pipe(catchError(() => of([] as JobDocumentRef[])), takeUntilDestroyed(this.destroyRef))
      .subscribe((docs) => this.documents.set(docs));
  }

  private show(r: ReportRevision): void {
    this.revision.set(r);
    const c = r.content;
    const eq = this.form.controls.equipment;
    eq.clear({ emitEvent: false });
    c.equipment.forEach((e) => eq.push(equipment(e), { emitEvent: false }));
    const at = this.form.controls.attendance;
    at.clear({ emitEvent: false });
    c.attendance.forEach((a) => at.push(attendance(a), { emitEvent: false }));
    this.form.reset({ vhfChannel: c.vhfChannel ?? '', summary: c.summary ?? '', remarks: c.remarks ?? '', attachmentDocumentIds: c.attachmentDocumentIds });
    if (r.allowedActions.includes('EDIT')) this.form.enable({ emitEvent: false });
    else this.form.disable({ emitEvent: false });
    this.changes.update((n) => n + 1);
  }

  private run(call: Observable<ReportRevision>, form?: FormGroup): void {
    this.saving.set(true);
    this.errorKey.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.dialog.set(null);
        this.show(r);
        this.load(this.jobId(), r.id);
      },
      error: (e: unknown) => this.fail(toProblem(e), form),
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    this.dialog.set(null);
    this.errorKey.set(form && applyServerErrors(form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
    if (problem.code === 'REPORT_INCOMPLETE' && this.revision()) this.select(this.revision()!.id);
  }
}

function equipment(e: { category: 'FENDER' | 'HOSE' | 'OTHER'; description: string; quantity: number | null }): EquipmentGroup {
  return new FormGroup({
    category: new FormControl(e.category, { nonNullable: true }),
    description: new FormControl(e.description, { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    quantity: new FormControl<number | null>(e.quantity, [Validators.min(0), Validators.max(999)]),
  });
}

function attendance(a: { capacity: string; name: string }): AttendanceGroup {
  return new FormGroup({
    capacity: new FormControl(a.capacity, { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    name: new FormControl(a.name, { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
  });
}
