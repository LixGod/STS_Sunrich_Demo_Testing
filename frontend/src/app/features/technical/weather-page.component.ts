import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, FormRecord, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { Observable } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
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
import {
  Comparison, LimitOverview, LimitRow, LimitSet, ReportKind, ReportSource, WeatherOverview, WeatherReport, WeatherReportInput,
  WeatherService, WeatherWarning,
} from './weather.service';

type OpenDialog = 'approve' | 'return' | 'report' | 'accept' | 'reject' | 'view' | null;

type LimitGroup = FormGroup<{
  parameterCode: FormControl<string | null>; operation: FormControl<string>; comparison: FormControl<Comparison>;
  threshold: FormControl<string | null>; action: FormControl<string>;
}>;
type TimeGroup = FormGroup<{ at: FormControl<Date | null>; values: FormRecord<FormControl<string | null>> }>;

const SOURCES: Record<ReportKind, ReportSource[]> = { FORECAST: ['PROVIDER_REPORT', 'OTHER_PROVIDER'], OBSERVATION: ['OBSERVATION'] };
const DECIMAL = /^-?\d{1,8}(\.\d{1,2})?$/;

/**
 * Weather and operating limits of a job (FR-TECH-04): the job's limits, entered and approved by a second person;
 * weather reports (uploaded provider forecasts, other forecasts, observations) accepted by a second person; and
 * warnings where a reported value is outside an approved limit. Warnings inform; they never block or decide.
 */
@Component({
  selector: 's2s-weather-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Dialog, InputText, Message, Select, Tag, Textarea, DateTimePickerComponent,
    DocumentViewerComponent, EmptyStateComponent, FormFieldComponent, PageHeaderComponent, ReasonDialogComponent, StatusTagComponent,
    UtcDateTimePipe,
  ],
  templateUrl: './weather-page.component.html',
  styleUrl: './weather-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WeatherPageComponent {
  private readonly service = inject(WeatherService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Job id (route parameter). */
  readonly jobId = input.required<string>();

  protected readonly limits = signal<LimitOverview | null>(null);
  protected readonly limitSet = signal<LimitSet | null>(null);
  protected readonly weather = signal<WeatherOverview | null>(null);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  protected readonly editingReport = signal<WeatherReport | null>(null);
  protected readonly reviewing = signal<WeatherReport | null>(null);
  protected readonly viewing = signal<ViewableFile | null>(null);
  protected readonly file = signal<File | null>(null);
  private readonly changes = signal(0);

  protected readonly can = computed(() => new Set(this.limitSet()?.allowedActions ?? []));
  protected readonly editable = computed(() => this.can().has('EDIT'));
  protected readonly dirty = computed(() => (this.changes(), this.limitForm.dirty));
  protected readonly canReadJpo = computed(() => this.auth.hasPermission(Permissions.techJpoRead));
  protected readonly timezone = computed(() => this.weather()?.timezone ?? 'UTC');
  protected readonly activeParameters = computed(() => (this.weather()?.parameters ?? []).filter((p) => p.active));

  protected readonly limitForm = new FormGroup({ rows: new FormArray<LimitGroup>([]) });

  protected readonly reportForm = new FormGroup({
    kind: new FormControl<ReportKind>('FORECAST', { nonNullable: true }),
    source: new FormControl<ReportSource>('PROVIDER_REPORT', { nonNullable: true }),
    providerName: new FormControl('', { nonNullable: true, validators: Validators.maxLength(100) }),
    issuedAt: new FormControl<Date | null>(null),
    validFrom: new FormControl<Date | null>(null),
    validTo: new FormControl<Date | null>(null),
    observedAt: new FormControl<Date | null>(null),
    area: new FormControl('', { nonNullable: true, validators: Validators.maxLength(200) }),
    notes: new FormControl('', { nonNullable: true, validators: Validators.maxLength(2000) }),
    times: new FormArray<TimeGroup>([]),
  });
  private readonly reportKind = signal<ReportKind>('FORECAST');

  protected readonly parameterOptions = computed(() =>
    this.activeParameters().map((p) => ({ value: p.code, label: `${p.label} (${p.unit})` })));

  protected readonly comparisonOptions = computed(() => {
    this.language.current();
    return (['ABOVE', 'BELOW'] as Comparison[]).map((value) => ({ value, label: this.translate.instant(`weather.comparison.${value}`) }));
  });

  protected readonly kindOptions = computed(() => {
    this.language.current();
    return (['FORECAST', 'OBSERVATION'] as ReportKind[]).map((value) => ({ value, label: this.translate.instant(`weather.kind.${value}`) }));
  });

  protected readonly sourceOptions = computed(() => {
    this.language.current();
    return SOURCES[this.reportKind()].map((value) => ({ value, label: this.translate.instant(`weather.source.${value}`) }));
  });

  constructor() {
    effect(() => {
      const id = this.jobId();
      untracked(() => this.load(id));
    });
    this.limitForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
    this.reportForm.controls.kind.valueChanges.pipe(takeUntilDestroyed()).subscribe((kind) => {
      this.reportKind.set(kind);
      const allowed = SOURCES[kind];
      if (!allowed.includes(this.reportForm.controls.source.value)) this.reportForm.controls.source.setValue(allowed[0]);
    });
  }

  // ---------- limits ----------

  protected selectLimits(id: string): void {
    this.service.limitSet(this.jobId(), id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (set) => this.showLimits(set),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  protected createLimits(): void {
    this.runLimits(this.service.createLimits(this.jobId()));
  }

  protected addLimit(): void {
    this.limitForm.controls.rows.push(limitGroup({ parameterCode: null, operation: '', comparison: 'ABOVE', threshold: null, action: '' }));
    this.limitForm.markAsDirty();
  }

  protected removeLimit(index: number): void {
    this.limitForm.controls.rows.removeAt(index);
    this.limitForm.markAsDirty();
  }

  protected unitOf(code: string | null): string {
    return this.weather()?.parameters.find((p) => p.code === code)?.unit ?? '';
  }

  protected saveLimits(): void {
    const set = this.limitSet();
    if (!set || this.limitForm.invalid || this.saving()) {
      this.limitForm.markAllAsTouched();
      return;
    }
    const rows = this.limitForm.getRawValue().rows.map((r) => ({
      parameterCode: r.parameterCode ?? '', operation: r.operation.trim(), comparison: r.comparison, threshold: (r.threshold ?? '').trim() || null,
      action: r.action.trim(),
    }));
    this.runLimits(this.service.saveLimits(this.jobId(), set, rows), this.limitForm);
  }

  protected submitLimits(): void {
    const set = this.limitSet();
    if (set) this.runLimits(this.service.submitLimits(this.jobId(), set));
  }

  protected decideLimits(decision: 'APPROVE' | 'RETURN', reason: string | null): void {
    const set = this.limitSet();
    if (set) this.runLimits(this.service.decideLimits(this.jobId(), set, decision, reason));
  }

  // ---------- reports ----------

  protected openReport(report: WeatherReport | null): void {
    this.dialogError.set(null);
    this.editingReport.set(report);
    this.file.set(null);
    const times = this.reportForm.controls.times;
    times.clear();
    const kind = report?.kind ?? 'FORECAST';
    this.reportKind.set(kind);
    // A new report starts at the current minute with a 24 h validity (times only; every value is entered by hand).
    const now = new Date();
    now.setSeconds(0, 0);
    this.reportForm.reset({
      kind, source: report?.source ?? SOURCES[kind][0], providerName: report?.providerName ?? '',
      issuedAt: report ? toDate(report.issuedAt) : now, validFrom: report ? toDate(report.validFrom) : now,
      validTo: report ? toDate(report.validTo) : new Date(now.getTime() + 24 * 3_600_000),
      observedAt: report ? toDate(report.observedAt) : now, area: report?.area ?? '', notes: report?.notes ?? '',
    });
    const byTime = new Map<string, Record<string, string>>();
    for (const v of report?.values ?? []) byTime.set(v.at, { ...(byTime.get(v.at) ?? {}), [v.parameterCode]: String(v.value) });
    if (byTime.size) [...byTime.entries()].forEach(([at, values]) => times.push(this.timeGroup(new Date(at), values)));
    else times.push(this.timeGroup(report ? null : now, {}));
    this.dialog.set('report');
  }

  protected addTime(): void {
    this.reportForm.controls.times.push(this.timeGroup(null, {}));
  }

  protected removeTime(index: number): void {
    this.reportForm.controls.times.removeAt(index);
  }

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    const problem = file && uploadProblem(file);
    this.errorKey.set(problem);
    this.file.set(problem ? null : file);
    if (problem) input.value = '';
  }

  protected saveReport(): void {
    if (this.reportForm.invalid || this.saving()) {
      this.reportForm.markAllAsTouched();
      return;
    }
    const v = this.reportForm.getRawValue();
    const forecast = v.kind === 'FORECAST';
    const input: WeatherReportInput = {
      kind: v.kind, source: v.source, providerName: v.providerName.trim() || null,
      issuedAt: forecast ? iso(v.issuedAt) : null, validFrom: forecast ? iso(v.validFrom) : null, validTo: forecast ? iso(v.validTo) : null,
      observedAt: forecast ? null : iso(v.observedAt), area: v.area.trim() || null, notes: v.notes.trim() || null,
      values: v.times.flatMap((t) => Object.entries(t.values)
        .filter(([, value]) => value !== null && String(value).trim() !== '')
        .map(([parameterCode, value]) => ({ at: iso(t.at) ?? '', parameterCode, value: String(value).trim() }))),
    };
    const report = this.editingReport();
    const call = report ? this.service.updateReport(this.jobId(), report, input) : this.service.recordReport(this.jobId(), input, this.file());
    this.runWeather(call, this.reportForm);
  }

  protected openReview(report: WeatherReport, decision: 'accept' | 'reject'): void {
    this.reviewing.set(report);
    this.dialog.set(decision);
  }

  protected review(decision: 'ACCEPT' | 'REJECT', reason: string | null): void {
    const report = this.reviewing();
    if (report) this.runWeather(this.service.reviewReport(this.jobId(), report, decision, reason));
  }

  protected view(report: WeatherReport): void {
    if (!report.file) return;
    this.viewing.set({ id: report.file.id, name: report.file.name, contentType: report.file.contentType });
    this.dialog.set('view');
  }

  /** Values of a report as a table: one row per time, one column per parameter used. */
  protected table(report: WeatherReport): { columns: string[]; rows: { at: string; cells: string[] }[] } {
    const columns = [...new Set(report.values.map((v) => v.parameterCode))];
    const times = [...new Set(report.values.map((v) => v.at))];
    return {
      columns,
      rows: times.map((at) => ({
        at,
        cells: columns.map((c) => {
          const value = report.values.find((v) => v.at === at && v.parameterCode === c);
          return value ? String(value.value) : '—';
        }),
      })),
    };
  }

  protected parameterLabel(code: string): string {
    const p = this.weather()?.parameters.find((x) => x.code === code);
    return p ? `${p.label} (${p.unit})` : code;
  }

  protected warningParams(w: WeatherWarning, at: string): Record<string, string> {
    return {
      kind: this.translate.instant(`weather.warningKind.${w.kind}`), at, parameter: w.parameterLabel.toLowerCase(), value: String(w.value),
      unit: w.unit, side: this.translate.instant(`weather.sideWord.${w.comparison}`), threshold: String(w.threshold), operation: w.operation,
      action: w.action,
    };
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
    this.errorKey.set(null);
    this.loadLimits(jobId);
    this.loadWeather(jobId);
  }

  private loadLimits(jobId: string, select?: string): void {
    this.service.limits(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => {
        this.limits.set(overview);
        const current = this.limitSet()?.id;
        const id = select ?? (overview.revisions.some((r) => r.id === current) ? current : overview.revisions[0]?.id);
        if (id) this.selectLimits(id);
      },
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private loadWeather(jobId: string): void {
    this.service.weather(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => this.weather.set(overview),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private showLimits(set: LimitSet): void {
    this.limitSet.set(set);
    const rows = this.limitForm.controls.rows;
    rows.clear({ emitEvent: false });
    set.rows.forEach((r: LimitRow) => rows.push(limitGroup({ parameterCode: r.parameterCode, operation: r.operation, comparison: r.comparison,
      threshold: String(r.threshold), action: r.action }), { emitEvent: false }));
    this.limitForm.markAsPristine();
    if (set.allowedActions.includes('EDIT')) this.limitForm.enable({ emitEvent: false });
    else this.limitForm.disable({ emitEvent: false });
    this.changes.update((n) => n + 1);
  }

  private timeGroup(at: Date | null, values: Record<string, string>): TimeGroup {
    const params = this.activeParameters();
    return new FormGroup({
      at: new FormControl<Date | null>(at, Validators.required),
      values: new FormRecord<FormControl<string | null>>(Object.fromEntries(params.map((p) =>
        [p.code, new FormControl<string | null>(values[p.code] ?? null, Validators.pattern(DECIMAL))]))),
    });
  }

  private runLimits(call: Observable<LimitSet>, form?: FormGroup): void {
    this.saving.set(true);
    this.errorKey.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (set) => {
        this.saving.set(false);
        this.dialog.set(null);
        this.showLimits(set);
        this.loadLimits(this.jobId(), set.id);
        this.loadWeather(this.jobId()); // warnings follow the approved limits
      },
      error: (error: unknown) => this.fail(toProblem(error), form),
    });
  }

  private runWeather(call: Observable<WeatherReport>, form?: FormGroup): void {
    this.saving.set(true);
    this.errorKey.set(null);
    this.dialogError.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.dialog.set(null);
        this.loadWeather(this.jobId());
      },
      error: (error: unknown) => this.fail(toProblem(error), form),
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    const key = form && applyServerErrors(form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem);
    if (this.dialog() === 'report') {
      this.dialogError.set(problem.errors?.length && key === 'errors.codes.VALIDATION_FAILED' ? 'weather.checkFields' : key);
    } else {
      this.dialog.set(null);
      this.errorKey.set(key);
    }
  }
}

function limitGroup(row: { parameterCode: string | null; operation: string; comparison: Comparison; threshold: string | null; action: string }): LimitGroup {
  return new FormGroup({
    parameterCode: new FormControl<string | null>(row.parameterCode, Validators.required),
    operation: new FormControl(row.operation, { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    comparison: new FormControl<Comparison>(row.comparison, { nonNullable: true }),
    threshold: new FormControl<string | null>(row.threshold, [Validators.required, Validators.pattern(DECIMAL)]),
    action: new FormControl(row.action, { nonNullable: true, validators: [Validators.required, Validators.maxLength(300)] }),
  });
}

function toDate(value: string | null | undefined): Date | null {
  return value ? new Date(value) : null;
}

function iso(value: Date | null): string | null {
  return value ? value.toISOString() : null;
}
