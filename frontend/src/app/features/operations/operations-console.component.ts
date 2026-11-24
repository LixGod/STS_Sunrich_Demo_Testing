import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { DatePicker } from 'primeng/datepicker';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { ProgressBar } from 'primeng/progressbar';
import { Select } from 'primeng/select';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { Observable, fromEvent } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
import { LanguageService } from '../../core/i18n/language.service';
// eslint-disable-next-line no-restricted-imports -- The console embeds the movements screen for the selected job.
import { MovementsPageComponent } from '../movements/movements-page.component';
import { DateTimePickerComponent } from '../../shared/components/date-time-picker/date-time-picker.component';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { fromUtc as date, toUtc as iso } from '../../shared/utils/zoned-time';
import {
  CargoGrade, Change, Delay, FileRef, Incident, ListItem, OperationEvent, OperationsOverview, OperationsService, SofRevision,
} from './operations.service';
import { OfflineEvent, OfflineEventsService } from './offline-events.service';

type Tab = 'overview' | 'events' | 'cargo' | 'incidents' | 'sof' | 'movements';
type OpenDialog = 'eta' | 'event' | 'void-event' | 'history' | 'reading' | 'delay' | 'void-delay' | 'incident' | 'close-incident' | 'view' | null;

type ActionGroup = FormGroup<{ text: FormControl<string>; ownerName: FormControl<string>; dueDate: FormControl<Date | null>; done: FormControl<boolean> }>;

const QUANTITY = /^\d{1,15}(\.\d{1,3})?$/;

/**
 * Live operations console of a job (FR-J07, FR-J08, FR-J09, FR-OPS-01/02): ETA revisions, the event log, grade-wise
 * cargo readings, delays and incidents, and the Statement of Facts. Phone-first; nothing is deleted (corrections and
 * voids carry a reason). The page refreshes when it regains focus and with the refresh button (no live push yet).
 */
@Component({
  selector: 's2s-operations-console',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Checkbox, DatePicker, Dialog, InputText, Message, ProgressBar, Select, Tag, Textarea,
    DateTimePickerComponent, DocumentViewerComponent, EmptyStateComponent, FormFieldComponent, PageHeaderComponent, ReasonDialogComponent,
    StatusTagComponent, UtcDateTimePipe, MovementsPageComponent,
  ],
  templateUrl: './operations-console.component.html',
  styleUrl: './operations-console.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OperationsConsoleComponent {
  private readonly service = inject(OperationsService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly offline = inject(OfflineEventsService);
  protected readonly pendingEvents = computed(() => this.offline.drafts().filter((r) => r.jobId === this.jobId()));
  protected readonly editingDraft = signal<OfflineEvent | null>(null);

  /** Job id (route parameter). */
  readonly jobId = input.required<string>();

  protected readonly data = signal<OperationsOverview | null>(null);
  protected readonly tab = signal<Tab>('events');
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  protected readonly editingEvent = signal<OperationEvent | null>(null);
  protected readonly editingDelay = signal<Delay | null>(null);
  protected readonly editingIncident = signal<Incident | null>(null);
  protected readonly target = signal<OperationEvent | Delay | Incident | null>(null);
  protected readonly grade = signal<CargoGrade | null>(null);
  protected readonly changes = signal<Change[]>([]);
  protected readonly viewing = signal<ViewableFile | null>(null);
  protected readonly tabs: Tab[] = ['events', 'overview', 'cargo', 'incidents', 'sof', 'movements'];

  protected readonly zone = computed(() => this.data()?.timezone ?? 'UTC');
  protected readonly can = computed(() => this.data()?.permissions);
  protected readonly canReadMovements = computed(() => this.auth.hasPermission(Permissions.opsMovementRead));
  protected readonly activeTypes = computed(() => (this.data()?.eventTypes ?? []).filter((t) => t.active));
  /** A shared event checks both vessels; a vessel event checks only that vessel. */
  protected readonly checklist = computed(() => {
    const events = (this.data()?.events ?? []).filter((e) => e.status === 'ACTIVE');
    const vessels = this.vesselOptions();
    return this.activeTypes().filter((t) => t.code !== 'OTHER')
      .map((type) => ({ type, vessels: vessels.map((vessel) => ({ ...vessel,
        event: events.find((e) => e.typeCode === type.code && (e.jobVesselId === vessel.value || e.jobVesselId === null)) ?? null,
      })) }));
  });
  protected readonly openIncidents = computed(() => (this.data()?.incidents ?? []).filter((i) => i.status !== 'CLOSED').length);
  protected readonly openDelays = computed(() => (this.data()?.delays ?? []).filter((d) => d.status === 'ACTIVE' && !d.endedAt).length);

  protected readonly eventForm = new FormGroup({
    typeCode: new FormControl<string | null>(null, Validators.required),
    jobVesselId: new FormControl<string | null>(null, Validators.required),
    occurredAt: new FormControl<Date | null>(null),
    startedAt: new FormControl<Date | null>(null),
    endedAt: new FormControl<Date | null>(null),
    remarks: new FormControl('', { nonNullable: true, validators: Validators.maxLength(1000) }),
    source: new FormControl('LIVE', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });
  private readonly eventType = signal<string | null>(null);
  protected readonly eventInterval = computed(() => this.data()?.eventTypes.find((t) => t.code === this.eventType())?.interval === true);

  protected readonly etaForm = new FormGroup({
    jobVesselId: new FormControl<string | null>(null, Validators.required),
    eta: new FormControl<Date | null>(null, Validators.required),
    timezone: new FormControl('UTC', { nonNullable: true }),
    source: new FormControl('AGENT', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly readingForm = new FormGroup({
    readAt: new FormControl<Date | null>(null, Validators.required),
    cumulative: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(QUANTITY)] }),
    kind: new FormControl('NORMAL', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
    remarks: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly delayForm = new FormGroup({
    categoryCode: new FormControl<string | null>(null, Validators.required),
    startedAt: new FormControl<Date | null>(null, Validators.required),
    endedAt: new FormControl<Date | null>(null),
    description: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(2000)] }),
    reason: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly incidentForm = new FormGroup({
    categoryCode: new FormControl<string | null>(null, Validators.required),
    severityCode: new FormControl<string | null>(null, Validators.required),
    occurredAt: new FormControl<Date | null>(null, Validators.required),
    description: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(2000)] }),
    immediateActions: new FormControl('', { nonNullable: true, validators: Validators.maxLength(2000) }),
    actions: new FormArray<ActionGroup>([]),
    reason: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly typeOptions = computed(() =>
    this.activeTypes().filter((t) => this.vesselOptionsFor(t.code).length > 0).map((t) => ({ value: t.code, label: t.label })),
  );
  protected readonly vesselOptions = computed(() => {
    this.language.current();
    return (this.data()?.vessels ?? []).map((v) => ({ value: v.jobVesselId,
      label: `${this.translate.instant('ops.role.' + v.role)}${v.name ? ' · ' + v.name : ''}` }));
  });
  protected readonly eventVesselOptions = computed(() => this.vesselOptionsFor(this.eventType()));
  protected readonly etaSourceOptions = computed(() => this.codes('ops.etaSource', ['VESSEL_MASTER', 'AGENT', 'OWNER_CHARTERER', 'OTHER']));
  protected readonly timezoneOptions = computed(() => {
    const zones = new Set([this.zone(), 'UTC', ...(this.data()?.vessels ?? []).map((v) => v.current?.timezone).filter((z): z is string => !!z)]);
    return [...zones].map((z) => ({ value: z, label: z }));
  });
  protected readonly kindOptions = computed(() =>
    this.codes('ops.readingKind', this.can()?.cargoCorrect ? ['NORMAL', 'FINAL', 'CORRECTION'] : ['NORMAL', 'FINAL']));
  protected readonly delayCategoryOptions = computed(() => this.options(this.data()?.delayCategories));
  protected readonly incidentCategoryOptions = computed(() => this.options(this.data()?.incidentCategories));
  protected readonly severityOptions = computed(() => this.options(this.data()?.incidentSeverities));

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const requested = params.get('tab') as Tab | null;
      if (requested && this.tabs.includes(requested)) this.tab.set(requested);
    });
    effect(() => {
      const id = this.jobId();
      untracked(() => this.load(id));
    });
    // Refresh when the user comes back to the page (no live push yet, 30s cooldown prevents request bursts on tab-switch).
    fromEvent(window, 'focus').pipe(takeUntilDestroyed()).subscribe(() => {
      if (!this.dialog() && Date.now() - this.lastLoadedAt > 30_000) this.load(this.jobId());
    });
    this.eventForm.controls.typeCode.valueChanges.pipe(takeUntilDestroyed()).subscribe((code) => {
      this.eventType.set(code);
      const allowed = this.vesselOptionsFor(code);
      if (!allowed.some((o) => o.value === this.eventForm.controls.jobVesselId.value)) {
        this.eventForm.controls.jobVesselId.setValue(allowed[0]?.value ?? null);
      }
    });
  }

  protected reload(): void {
    this.load(this.jobId());
  }

  private vesselOptionsFor(code: string | null): { value: string; label: string }[] {
    const vessels = this.vesselOptions();
    const editingId = this.editingEvent()?.id;
    const taken = (this.data()?.events ?? []).filter(
      (e) => e.status === 'ACTIVE' && e.typeCode === code && e.id !== editingId,
    );
    if (taken.some((e) => e.jobVesselId === null)) return [];
    const remaining = vessels.filter((v) => !taken.some((e) => e.jobVesselId === v.value));
    return taken.length
      ? remaining
      : [{ value: 'BOTH', label: this.translate.instant('ops.bothVessels') }, ...remaining];
  }

  protected eventsForVessel(id: string): OperationEvent[] {
    return (this.data()?.events ?? []).filter((event) => event.jobVesselId === id || event.jobVesselId === null);
  }

  protected progress(grade: CargoGrade): number {
    const planned = Number(grade.planned);
    return planned > 0 ? Math.min(100, Math.round((Number(grade.cumulative ?? 0) / planned) * 100)) : 0;
  }

  // ---------- ETA ----------

  protected openEta(): void {
    this.dialogError.set(null);
    const first = this.data()?.vessels[0];
    this.etaForm.reset({ jobVesselId: first?.jobVesselId ?? null, eta: null, timezone: first?.current?.timezone ?? this.zone(), source: 'AGENT', note: '' });
    this.dialog.set('eta');
  }

  protected saveEta(): void {
    if (this.etaForm.invalid || this.saving()) {
      this.etaForm.markAllAsTouched();
      return;
    }
    const v = this.etaForm.getRawValue();
    const etaUtc = iso(v.eta, v.timezone);
    if (!etaUtc) { this.dialogError.set('ops.invalidTime'); return; }
    this.run(this.service.recordEta(this.jobId(), { jobVesselId: v.jobVesselId!, etaUtc, timezone: v.timezone,
      source: v.source, note: v.note.trim() || null }), this.etaForm);
  }

  // ---------- events ----------

  protected openEvent(event: OperationEvent | null, typeCode?: string): void {
    this.editingDraft.set(null);
    this.dialogError.set(null);
    this.editingEvent.set(event);
    const now = date(new Date().toISOString(), this.zone());
    const code = event?.typeCode ?? typeCode ?? null;
    this.eventType.set(code);
    const defaultVessel = event ? (event.jobVesselId ?? 'BOTH') : (this.vesselOptionsFor(code)[0]?.value ?? null);
    this.eventForm.reset({
      typeCode: code, jobVesselId: defaultVessel,
      occurredAt: event ? date(event.occurredAt, this.zone()) : now, startedAt: event ? date(event.startedAt, this.zone()) : now,
      endedAt: event ? date(event.endedAt, this.zone()) : null,
      remarks: event?.remarks ?? '', source: event?.source ?? 'LIVE', reason: '',
    });
    this.eventForm.controls.reason.setValidators(event ? [Validators.required, Validators.maxLength(500)] : [Validators.maxLength(500)]);
    this.eventForm.controls.reason.updateValueAndValidity();
    this.dialog.set('event');
  }

  protected saveEvent(): void {
    if (this.eventForm.invalid || this.saving()) {
      this.eventForm.markAllAsTouched();
      return;
    }
    const v = this.eventForm.getRawValue();
    const interval = this.eventInterval();
    const input = {
      typeCode: v.typeCode!, jobVesselId: v.jobVesselId === 'BOTH' ? null : v.jobVesselId,
      occurredAt: interval ? null : iso(v.occurredAt, this.zone()),
      startedAt: interval ? iso(v.startedAt, this.zone()) : null, endedAt: interval ? iso(v.endedAt, this.zone()) : null,
      remarks: v.remarks.trim() || null,
      source: v.source, reason: v.reason.trim() || null,
    };
    if ((interval ? !input.startedAt || (v.endedAt && !input.endedAt) : !input.occurredAt)) {
      this.dialogError.set('ops.invalidTime'); return;
    }
    const event = this.editingEvent();
    if (event) { this.run(this.service.correctEvent(this.jobId(), event, input), this.eventForm); return; }
    try {
      const draft = this.offline.save(this.jobId(), input, this.editingDraft() ?? undefined);
      this.dialog.set(null);
      if (navigator.onLine) this.syncDraft(draft);
    } catch (error: unknown) { this.dialogError.set(error instanceof Error ? error.message : 'errors.saveFailed'); }
  }

  /** A tick records this vessel's step now; "Change time" corrects it afterwards. */
  protected tick(typeCode: string, jobVesselId: string): void {
    if (this.saving()) return;
    const now = new Date().toISOString();
    const interval = this.data()?.eventTypes.find((t) => t.code === typeCode)?.interval === true;
    try {
      const draft = this.offline.save(this.jobId(), { typeCode, jobVesselId, occurredAt: interval ? null : now,
        startedAt: interval ? now : null, endedAt: null, remarks: null, source: 'LIVE', reason: null });
      if (navigator.onLine) this.syncDraft(draft);
    } catch (error: unknown) { this.errorKey.set(error instanceof Error ? error.message : 'errors.saveFailed'); }
  }

  protected reviewDraft(row: OfflineEvent): void {
    this.openEvent(null, row.input.typeCode);
    this.editingDraft.set(row);
    const value = row.input;
    this.eventForm.patchValue({ ...value, jobVesselId: value.jobVesselId ?? 'BOTH', remarks: value.remarks ?? '', reason: value.reason ?? '', occurredAt: date(value.occurredAt, this.zone()),
      startedAt: date(value.startedAt, this.zone()), endedAt: date(value.endedAt, this.zone()) });
  }
  protected syncDraft(row: OfflineEvent, reason = ''): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.offline.sync(row, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.reload(); },
      error: () => this.saving.set(false),
    });
  }

  protected openVoid(target: OperationEvent | Delay, kind: 'void-event' | 'void-delay'): void {
    this.target.set(target);
    this.dialog.set(kind);
  }

  protected voidEvent(reason: string | null): void {
    const event = this.target() as OperationEvent | null;
    if (event && reason) this.run(this.service.voidEvent(this.jobId(), event, reason));
  }

  protected showHistory(kind: 'events' | 'delays' | 'incidents', id: string): void {
    this.changes.set([]);
    this.service.history(this.jobId(), kind, id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => {
        this.changes.set(list);
        this.dialog.set('history');
      },
      error: (e: unknown) => this.errorKey.set(problemKey(toProblem(e))),
    });
  }

  protected entries(before: Record<string, unknown>): { key: string; value: string }[] {
    return Object.entries(before).map(([key, value]) => ({
      key, value: value === null || value === undefined ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value),
    }));
  }

  // ---------- cargo ----------

  protected openReading(grade: CargoGrade): void {
    this.dialogError.set(null);
    this.grade.set(grade);
    const now = date(new Date().toISOString(), this.zone());
    this.readingForm.reset({ readAt: now, cumulative: '', kind: 'NORMAL', reason: '', remarks: '' });
    this.dialog.set('reading');
  }

  protected saveReading(): void {
    const grade = this.grade();
    const v = this.readingForm.getRawValue();
    if (v.kind === 'CORRECTION' && !v.reason.trim()) this.readingForm.controls.reason.setErrors({ required: true });
    if (!grade || this.readingForm.invalid || this.saving()) {
      this.readingForm.markAllAsTouched();
      return;
    }
    const readAt = iso(v.readAt, this.zone());
    if (!readAt) { this.dialogError.set('ops.invalidTime'); return; }
    this.run(this.service.addReading(this.jobId(), grade.lineNo, { readAt, cumulative: v.cumulative.trim(),
      unitCode: grade.unitCode ?? '', kind: v.kind, reason: v.reason.trim() || null, certifiedDocumentId: null, remarks: v.remarks.trim() || null }),
    this.readingForm);
  }

  // ---------- delays and incidents ----------

  protected openDelay(delay: Delay | null): void {
    this.dialogError.set(null);
    this.editingDelay.set(delay);
    const now = date(new Date().toISOString(), this.zone());
    this.delayForm.reset({ categoryCode: delay?.categoryCode ?? null, startedAt: delay ? date(delay.startedAt, this.zone()) : now,
      endedAt: delay ? date(delay.endedAt, this.zone()) : null, description: delay?.description ?? '', reason: '' });
    this.dialog.set('delay');
  }

  protected saveDelay(): void {
    const delay = this.editingDelay();
    const v = this.delayForm.getRawValue();
    if (delay && !v.reason.trim()) this.delayForm.controls.reason.setErrors({ required: true });
    if (this.delayForm.invalid || this.saving()) {
      this.delayForm.markAllAsTouched();
      return;
    }
    const startedAt = iso(v.startedAt, this.zone());
    const endedAt = iso(v.endedAt, this.zone());
    if (!startedAt || (v.endedAt && !endedAt)) { this.dialogError.set('ops.invalidTime'); return; }
    const input = { categoryCode: v.categoryCode!, startedAt, endedAt, description: v.description.trim(),
      reason: v.reason.trim() || null };
    this.run(delay ? this.service.updateDelay(this.jobId(), delay, input) : this.service.createDelay(this.jobId(), input), this.delayForm);
  }

  protected voidDelay(reason: string | null): void {
    const delay = this.target() as Delay | null;
    if (delay && reason) this.run(this.service.voidDelay(this.jobId(), delay, reason));
  }

  protected openIncident(incident: Incident | null): void {
    this.dialogError.set(null);
    this.editingIncident.set(incident);
    const now = date(new Date().toISOString(), this.zone());
    const actions = this.incidentForm.controls.actions;
    actions.clear();
    (incident?.actions ?? []).forEach((a) => actions.push(actionGroup(a.text, a.ownerName ?? '', a.dueDate ? fromDay(a.dueDate) : null, !!a.doneAt)));
    this.incidentForm.patchValue({ categoryCode: incident?.categoryCode ?? null, severityCode: incident?.severityCode ?? null,
      occurredAt: incident ? date(incident.occurredAt, this.zone()) : now, description: incident?.description ?? '',
      immediateActions: incident?.immediateActions ?? '', reason: '' });
    this.incidentForm.markAsUntouched();
    this.dialog.set('incident');
  }

  protected addAction(): void {
    this.incidentForm.controls.actions.push(actionGroup('', '', null, false));
  }

  protected removeAction(index: number): void {
    this.incidentForm.controls.actions.removeAt(index);
  }

  protected saveIncident(): void {
    const incident = this.editingIncident();
    const v = this.incidentForm.getRawValue();
    if (incident && !v.reason.trim()) this.incidentForm.controls.reason.setErrors({ required: true });
    if (this.incidentForm.invalid || this.saving()) {
      this.incidentForm.markAllAsTouched();
      return;
    }
    const occurredAt = iso(v.occurredAt, this.zone());
    if (!occurredAt) { this.dialogError.set('ops.invalidTime'); return; }
    const input = {
      categoryCode: v.categoryCode!, severityCode: v.severityCode!, occurredAt, description: v.description.trim(),
      immediateActions: v.immediateActions.trim() || null, reason: v.reason.trim() || null,
      actions: v.actions.map((a) => ({ text: a.text.trim(), ownerName: a.ownerName.trim() || null, dueDate: a.dueDate ? day(a.dueDate) : null, done: a.done })),
    };
    this.run(incident ? this.service.updateIncident(this.jobId(), incident, input) : this.service.createIncident(this.jobId(), input), this.incidentForm);
  }

  protected startReview(incident: Incident): void {
    this.run(this.service.reviewIncident(this.jobId(), incident, 'START_REVIEW', null));
  }

  protected openClose(incident: Incident): void {
    this.target.set(incident);
    this.dialog.set('close-incident');
  }

  protected closeIncident(note: string | null): void {
    const incident = this.target() as Incident | null;
    if (incident && note) this.run(this.service.reviewIncident(this.jobId(), incident, 'CLOSE', note));
  }

  protected attach(event: Event, ownerType: 'DELAY' | 'INCIDENT', ownerId: string): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const problem = uploadProblem(file);
    if (problem) { this.errorKey.set(problem); input.value = ''; return; }
    input.value = '';
    this.run(this.service.attach(this.jobId(), ownerType, ownerId, file));
  }

  protected view(file: FileRef): void {
    this.viewing.set({ id: file.fileId, name: file.name, contentType: file.contentType });
    this.dialog.set('view');
  }

  // ---------- SOF ----------

  protected sofUrl(sof: SofRevision | null): string {
    return this.service.sofPdfUrl(this.jobId(), sof);
  }

  protected prepareSof(): void {
    this.run(this.service.prepareSof(this.jobId()));
  }

  protected sofAction(sof: SofRevision, action: 'issue' | 'withdraw'): void {
    this.run(this.service.sofAction(this.jobId(), sof, action));
  }

  protected closeDialog(open: boolean): void {
    if (!open) {
      this.dialog.set(null);
      this.viewing.set(null);
    }
  }

  // ---------- helpers ----------

  private lastLoadedAt = 0;

  private load(jobId: string): void {
    this.lastLoadedAt = Date.now();
    this.loading.set(true);
    this.service.overview(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.loading.set(false);
        this.data.set(data);
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.errorKey.set(problemKey(toProblem(e)) === 'errors.saveFailed' ? 'errors.loadFailed' : problemKey(toProblem(e)));
      },
    });
  }

  /** Runs a change, then reloads the console; a dialog stays open with its error if it fails. */
  private run(call: Observable<unknown>, form?: FormGroup): void {
    this.saving.set(true);
    this.errorKey.set(null);
    this.dialogError.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.dialog.set(null);
        this.load(this.jobId());
      },
      error: (e: unknown) => this.fail(toProblem(e), form),
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    const key = form && applyServerErrors(form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem);
    if (form && this.dialog()) this.dialogError.set(key);
    else {
      this.dialog.set(null);
      this.errorKey.set(key);
    }
  }

  private codes(prefix: string, codes: string[]): { value: string; label: string }[] {
    this.language.current();
    return codes.map((value) => ({ value, label: this.translate.instant(`${prefix}.${value}`) }));
  }

  private options(items: ListItem[] | undefined): { value: string; label: string }[] {
    return (items ?? []).filter((i) => i.active).map((i) => ({ value: i.code, label: i.label }));
  }
}

function actionGroup(text: string, ownerName: string, dueDate: Date | null, done: boolean): ActionGroup {
  return new FormGroup({
    text: new FormControl(text, { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    ownerName: new FormControl(ownerName, { nonNullable: true, validators: Validators.maxLength(150) }),
    dueDate: new FormControl<Date | null>(dueDate),
    done: new FormControl(done, { nonNullable: true }),
  });
}

/** `YYYY-MM-DD` of a date picker value, as picked. */
function day(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function fromDay(value: string): Date {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}
