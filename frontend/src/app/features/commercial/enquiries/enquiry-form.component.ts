import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { AutoComplete, AutoCompleteCompleteEvent } from 'primeng/autocomplete';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { MultiSelect } from 'primeng/multiselect';
import { Select } from 'primeng/select';
import { Subject, catchError, debounceTime, map, of, startWith, switchMap } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { DateTimePickerComponent } from '../../../shared/components/date-time-picker/date-time-picker.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../../shared/pipes/utc-datetime.pipe';
import { COUNTRIES } from '../../../shared/data/countries';
import {
  EnquiryDetail, EnquiryDraft, EnquiryDraftRequest, EnquiryDraftService, EnquiryRequest, EnquiryService, EnquiryStatus, EnquirySummary, OPERATION_TYPES, OperationType, REASON_REQUIRED,
  fromLocalDateTime, newIdempotencyKey, toLocalDateTime,
} from './enquiry.service';
import { EnquiryTabsComponent } from './enquiry-tabs.component';
import { ClientQuickAddComponent } from './client-quick-add.component';
import { VesselQuickAddComponent } from './vessel-quick-add.component';

type CargoRow = FormGroup<{
  cargoGradeId: FormControl<string | null>;
  quantity: FormControl<number | null>;
  unitId: FormControl<string | null>;
}>;
type SpecialRow = FormGroup<{
  description: FormControl<string>;
  paid: FormControl<boolean>;
  salePriceUsd: FormControl<number | null>;
}>;

/**
 * Autocomplete selection. PrimeNG stores `option.value` when options have a `value` field, which would lose the label;
 * using `id` keeps the whole selection (and its label) in the form control.
 */
export interface Picked {
  id: string;
  label: string;
}

type VesselGroup = FormGroup<{ vessel: FormControl<Picked | null>; eta: FormControl<Date | null> }>;

const MAX_CARGO_LINES = 10;

/**
 * Enquiry registration and detail (FR-J01): client and contact, mother/daughter vessels with ETAs in the branch time zone,
 * multi-grade cargo, duplicate warning, lifecycle actions and history. Read-only once quoted, lost or cancelled.
 */
@Component({
  selector: 's2s-enquiry-form',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, AutoComplete, Button, InputNumber, InputText, Message, MultiSelect, Select,
    UtcDateTimePipe, AuditTrailComponent, DateTimePickerComponent, FormFieldComponent, PageHeaderComponent, ReasonDialogComponent, StatusTagComponent,
    EnquiryTabsComponent, VesselQuickAddComponent, ClientQuickAddComponent,
  ],
  templateUrl: './enquiry-form.component.html',
  styleUrl: './enquiry-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnquiryFormComponent {
  private readonly service = inject(EnquiryService);
  private readonly drafts = inject(EnquiryDraftService);
  private readonly lookups = inject(LookupService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private autoCargoTypes: OperationType[] = [];
  private settingAutoCargoTypes = false;
  private loadingClientFromRecord = false;

  /** Route parameter; absent on the "new" route. */
  readonly id = input<string | undefined>(undefined);
  readonly draftId = input<string | undefined>(undefined);

  protected readonly record = signal<EnquiryDetail | null>(null);
  protected readonly draft = signal<EnquiryDraft | null>(null);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly duplicates = signal<EnquirySummary[]>([]);
  protected readonly pendingTransition = signal<EnquiryStatus | null>(null);
  protected readonly quickAddFor = signal<'mother' | 'daughter' | null>(null);
  protected readonly quickAddClientVisible = signal(false);
  protected readonly clientSuggestions = signal<Picked[]>([]);
  protected readonly vesselSuggestions = signal<Picked[]>([]);
  protected readonly contactOptions = signal<LookupOption[]>([]);
  protected readonly clientDetails = signal<{ countryCode?: string | null; phone?: string | null; phones?: string[] } | null>(null);
  protected readonly clientCountry = computed(() => {
    const code = this.clientDetails()?.countryCode?.trim().toUpperCase();
    return code ? (COUNTRIES.find((country) => country.code === code)?.name ?? code) : '';
  });
  protected readonly clientPhone = computed(() => {
    const details = this.clientDetails();
    return details?.phones?.length ? details.phones.join(', ') : (details?.phone ?? '');
  });
  private readonly addedClients = new Map<string, { countryCode?: string | null; phone?: string | null; phones?: string[] }>();
  private readonly addedContacts = new Map<string, LookupOption>();

  /** One key per create page, so a double-clicked or retried save cannot create two enquiries. */
  private readonly idempotencyKey = newIdempotencyKey();

  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.enquiryWrite));
  protected readonly canAddVessel = computed(() => this.auth.hasPermission(Permissions.enquiryWrite));
  protected readonly canReadAudit = computed(() => this.auth.hasPermission(Permissions.auditRead));
  protected readonly editable = computed(() => this.canWrite() && (!this.record() || this.record()!.editable));
  protected readonly maxCargo = MAX_CARGO_LINES;

  private readonly noOptions: LookupOption[] = [];
  protected readonly branches = toSignal(this.lookups.branches().pipe(catchError(() => of(this.noOptions))), { initialValue: this.noOptions });
  protected readonly grades = toSignal(this.lookups.cargoGrades().pipe(catchError(() => of(this.noOptions))), { initialValue: this.noOptions });
  protected readonly units = toSignal(this.lookups.units().pipe(catchError(() => of(this.noOptions))), { initialValue: this.noOptions });

  protected readonly form = this.fb.group({
    branchId: new FormControl<string | null>(null, Validators.required),
    receivedAt: new FormControl<Date | null>(new Date()),
    client: new FormControl<Picked | null>(null),
    clientContactId: new FormControl<string | null>(null),
    cargoTypes: new FormControl<OperationType[]>([], { nonNullable: true }),
    expectedDurationHours: new FormControl<number | null>(null, [Validators.min(0.1), Validators.max(99999)]),
    sourceReference: ['', Validators.maxLength(255)],
    mother: this.vesselGroup(),
    daughter: this.vesselGroup(),
    cargo: new FormArray<CargoRow>([this.cargoRow()]),
    specialItems: new FormArray<SpecialRow>([]),
  });

  private readonly clientId = toSignal(this.form.controls.client.valueChanges.pipe(map((c) => c?.id ?? null)), { initialValue: null });
  /** Why a dependent list is empty (its parent is not chosen yet, or has nothing to offer); null when it has options. */
  protected readonly branchHint = computed(() => (this.branches().length ? null : 'enquiry.noBranches'));
  protected readonly contactHint = computed(() => (!this.clientId() ? 'enquiry.chooseClientFirst' : this.contactOptions().length ? null : 'enquiry.noContacts'));
  protected readonly operationOptions = toSignal(this.lookups.operationTypes().pipe(catchError(() => of([] as LookupOption[]))), { initialValue: [] });
  protected readonly durationOptions = computed(() => {
    this.language.current();
    return [24, 48, 72].map((value) => ({ value, label: this.translate.instant(`masters.tariff.hours${value}`) }));
  });

  private readonly clientQuery = new Subject<string>();
  private readonly vesselQuery = new Subject<string>();

  constructor() {
    // Cargo grade categories and operation types use the same values; fill the operation type while it remains automatic.
    effect(() => {
      this.grades();
      this.operationOptions();
      untracked(() => this.syncCargoTypesFromCargo());
    });

    effect(() => {
      const id = this.id();
      const draftId = this.draftId();
      untracked(() => (draftId ? this.loadDraft(draftId) : id ? this.load(id) : this.applyAccess()));
    });

    this.clientQuery.pipe(
      debounceTime(250),
      switchMap((q) => this.lookups.searchClients(q).pipe(catchError(() => of(this.noOptions)))),
      takeUntilDestroyed(),
    ).subscribe((options) => this.clientSuggestions.set(options.map(toPicked)));

    this.vesselQuery.pipe(
      debounceTime(250),
      switchMap((q) => this.lookups.searchVessels(q).pipe(catchError(() => of(this.noOptions)))),
      takeUntilDestroyed(),
    ).subscribe((options) => this.vesselSuggestions.set(options.map(toPicked)));

    // Client details and contacts follow the chosen client; keep quick-add details if detail reload fails.
    this.form.controls.client.valueChanges.pipe(
      startWith(this.form.controls.client.value),
      switchMap((client) => {
        if (!client?.id) return of(null);
        const fallback = this.addedClients.get(client.id) ?? null;
        return this.lookups.clientDetails(client.id).pipe(
          map((details) => ({
            countryCode: details.countryCode ?? fallback?.countryCode ?? null,
            phone: details.phone ?? fallback?.phone ?? null,
            phones: details.phones?.length ? details.phones : fallback?.phones,
          })),
          catchError(() => of(fallback)),
        );
      }),
      takeUntilDestroyed(),
    ).subscribe((details) => this.clientDetails.set(details));

    this.form.controls.client.valueChanges.pipe(
      startWith(this.form.controls.client.value),
      switchMap((client) => {
        if (!client?.id) return of(this.noOptions);
        const fallback = this.addedContacts.get(client.id);
        return this.lookups.contacts(client.id).pipe(
          map((contacts) => (fallback && !contacts.some((c) => c.value === fallback.value) ? [fallback, ...contacts] : contacts)),
          catchError(() => of(fallback ? [fallback] : this.noOptions)),
        );
      }),
      takeUntilDestroyed(),
    ).subscribe((contacts) => {
      this.contactOptions.set(contacts);
      const contact = this.form.controls.clientContactId;
      if (contact.value && !contacts.some((c) => c.value === contact.value)) contact.setValue(null);
      if (!contact.value && !this.loadingClientFromRecord && this.editable() && contacts.length) {
        const preferred = contacts.find((c) => c.meta?.['primary'] === 'true') ?? (contacts.length === 1 ? contacts[0] : null);
        if (preferred) contact.setValue(preferred.value);
      }
    });

    // New enquiry: when there is only one option, pick it for the user.
    effect(() => {
      const only = (options: readonly { value: string }[], control: FormControl<string | null>) => {
        if (!this.id() && options.length === 1 && !control.value && control.enabled) control.setValue(options[0].value);
      };
      only(this.branches(), this.form.controls.branchId);
    });

    // Possible duplicates: another open enquiry for the same two vessels (warning only).
    const pair = () => [this.form.controls.mother.controls.vessel.value?.id, this.form.controls.daughter.controls.vessel.value?.id];
    this.form.controls.mother.controls.vessel.valueChanges.pipe(startWith(null), takeUntilDestroyed()).subscribe(() => this.checkDuplicates(pair()));
    this.form.controls.daughter.controls.vessel.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.checkDuplicates(pair()));

    this.form.controls.cargoTypes.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      if (!this.settingAutoCargoTypes) this.autoCargoTypes = [];
    });
  }

  protected get cargoRows(): CargoRow[] {
    return this.form.controls.cargo.controls;
  }

  protected get specialRows(): SpecialRow[] { return this.form.controls.specialItems.controls; }

  protected addSpecial(): void { if (this.specialRows.length < 20) this.form.controls.specialItems.push(this.specialRow()); }
  protected removeSpecial(index: number): void { this.form.controls.specialItems.removeAt(index); }

  protected searchClients(event: AutoCompleteCompleteEvent): void {
    this.clientQuery.next(event.query);
  }

  protected searchVessels(event: AutoCompleteCompleteEvent): void {
    this.vesselQuery.next(event.query);
  }

  protected addCargo(): void {
    if (this.form.controls.cargo.length < MAX_CARGO_LINES) {
      this.form.controls.cargo.push(this.cargoRow());
      this.syncCargoTypesFromCargo();
    }
  }

  protected removeCargo(index: number): void {
    if (this.form.controls.cargo.length > 1) {
      this.form.controls.cargo.removeAt(index);
      this.syncCargoTypesFromCargo();
    }
  }

  protected vesselAdded(option: LookupOption): void {
    const target = this.quickAddFor();
    if (target) this.form.controls[target].controls.vessel.setValue(toPicked(option));
  }

  protected clientAdded(created: { client: { id: string; legalName: string; code: string; countryCode: string | null; phone: string | null; phones?: string[] }; contact: { id: string; email: string } }): void {
    const details = { countryCode: created.client.countryCode, phone: created.client.phone, phones: created.client.phones };
    const contactOption: LookupOption = {
      value: created.contact.id,
      label: created.contact.email ? `${created.client.legalName} <${created.contact.email}>` : created.client.legalName,
    };
    this.addedClients.set(created.client.id, details);
    this.addedContacts.set(created.client.id, contactOption);
    this.clientDetails.set(details);
    this.contactOptions.set([contactOption]);
    this.form.controls.client.setValue({ id: created.client.id, label: `${created.client.legalName} (${created.client.code})` });
    this.form.controls.clientContactId.setValue(created.contact.id);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      this.errorKey.set('errors.codes.VALIDATION_FAILED');
      return;
    }
    const request = this.toRequest();
    const current = this.record();
    this.saving.set(true);
    this.errorKey.set(null);
    const call = current ? this.service.update(current.id, current.version, request) : this.service.create(request, this.idempotencyKey);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        const finish = () => {
          this.saving.set(false);
          this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved'), detail: saved.reference });
          if (!current) void this.router.navigate(['/commercial/enquiries', saved.id], { replaceUrl: true });
          else this.show(saved);
        };
        const draft = this.draft();
        if (draft) this.drafts.delete(draft.id, draft.version).subscribe({
          next: finish,
          error: () => {
            this.messages.add({ severity: 'warn', summary: this.translate.instant('enquiry.draftCleanupFailed') });
            finish();
          },
        });
        else finish();
      },
      error: (error: unknown) => this.fail(toProblem(error)),
    });
  }

  protected saveDraft(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.errorKey.set(null);
    const current = this.draft();
    const request = this.toDraftRequest();
    const call = current ? this.drafts.update(current.id, current.version, request) : this.drafts.create(request);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (draft) => {
        this.saving.set(false);
        this.draft.set(draft);
        this.messages.add({ severity: 'success', summary: this.translate.instant('enquiry.draftSaved') });
        if (!current) void this.router.navigate(['/commercial/enquiries/drafts', draft.id], { replaceUrl: true });
      },
      error: (error: unknown) => this.fail(toProblem(error)),
    });
  }

  private loadDraft(id: string): void {
    this.loading.set(true);
    this.drafts.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (draft) => {
        this.loading.set(false);
        this.draft.set(draft);
        const d = draft.data;
        this.form.controls.cargo.clear();
        for (const row of d.cargo) this.form.controls.cargo.push(this.cargoRow(row.cargoGradeId, row.quantity ? Number(row.quantity) : null, row.unitId));
        if (!d.cargo.length) this.form.controls.cargo.push(this.cargoRow());
        this.form.controls.specialItems.clear();
        for (const item of d.specialItems ?? []) this.form.controls.specialItems.push(this.specialRow(item.description, item.paid, item.salePriceUsd ? Number(item.salePriceUsd) : null));
        this.loadingClientFromRecord = true;
        this.form.patchValue({
          branchId: d.branchId, receivedAt: d.receivedAt ? new Date(d.receivedAt) : null,
          client: d.clientId ? { id: d.clientId, label: d.clientLabel ?? d.clientId } : null,
          clientContactId: d.clientContactId, cargoTypes: d.cargoTypes?.length ? d.cargoTypes : d.operationType ? [d.operationType] : [],
          expectedDurationHours: d.expectedDurationHours ? Number(d.expectedDurationHours) : null,
          sourceReference: d.sourceReference ?? '',
          mother: { vessel: d.motherVessel?.vesselId ? { id: d.motherVessel.vesselId, label: d.motherVesselLabel ?? d.motherVessel.vesselId } : null,
            eta: fromLocalDateTime(d.motherVessel?.etaLocal ?? null) },
          daughter: { vessel: d.daughterVessel?.vesselId ? { id: d.daughterVessel.vesselId, label: d.daughterVesselLabel ?? d.daughterVessel.vesselId } : null,
            eta: fromLocalDateTime(d.daughterVessel?.etaLocal ?? null) },
        });
        this.loadingClientFromRecord = false;
        this.applyAccess();
      },
      error: () => { this.loading.set(false); this.errorKey.set('errors.loadFailed'); },
    });
  }

  private toDraftRequest(): EnquiryDraftRequest {
    const v = this.form.getRawValue();
    const vessel = (group: typeof v.mother) => group.vessel || group.eta
      ? { vesselId: group.vessel?.id ?? null, etaLocal: toLocalDateTime(group.eta) } : null;
    return {
      branchId: v.branchId, receivedAt: v.receivedAt?.toISOString() ?? null,
      clientId: v.client?.id ?? null, clientLabel: v.client?.label ?? null,
      clientContactId: v.clientContactId,
      operationType: inferOperationType(v.cargoTypes), cargoTypes: v.cargoTypes,
      expectedDurationHours: v.expectedDurationHours == null ? null : String(v.expectedDurationHours),
      scopeOfServices: this.draft()?.data.scopeOfServices ?? null,
      specialRequirements: this.draft()?.data.specialRequirements ?? null,
      sourceReference: v.sourceReference.trim() || null,
      motherVessel: vessel(v.mother), motherVesselLabel: v.mother.vessel?.label ?? null,
      daughterVessel: vessel(v.daughter), daughterVesselLabel: v.daughter.vessel?.label ?? null,
      cargo: v.cargo.map(row => ({ cargoGradeId: row.cargoGradeId, quantity: row.quantity == null ? null : String(row.quantity), unitId: row.unitId })),
      specialItems: v.specialItems.map(item => ({ description: item.description.trim(), paid: item.paid,
        salePriceUsd: item.paid && item.salePriceUsd != null ? String(item.salePriceUsd) : null })),
    };
  }

  protected startTransition(to: EnquiryStatus): void {
    this.pendingTransition.set(to);
  }

  protected reasonRequired(to: EnquiryStatus | null): boolean {
    return !!to && REASON_REQUIRED.includes(to);
  }

  protected confirmTransition(reason: string | null): void {
    const current = this.record();
    const to = this.pendingTransition();
    if (!current || !to) return;
    this.saving.set(true);
    this.service.transition(current.id, current.version, to, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.pendingTransition.set(null);
        this.show(saved);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.pendingTransition.set(null);
        this.errorKey.set(problemKey(toProblem(error)));
      },
    });
  }

  protected closeTransition(open: boolean): void {
    if (!open) this.pendingTransition.set(null);
  }

  protected reload(): void {
    const id = this.id();
    if (id) this.load(id);
  }

  private load(id: string): void {
    this.loading.set(true);
    this.errorKey.set(null);
    this.service.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (enquiry) => {
        this.loading.set(false);
        this.show(enquiry);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  private show(enquiry: EnquiryDetail): void {
    this.record.set(enquiry);
    const cargo = this.form.controls.cargo;
    cargo.clear();
    for (const line of enquiry.cargo) {
      cargo.push(this.cargoRow(line.cargoGradeId, line.quantity, line.unitId));
    }
    if (cargo.length === 0) cargo.push(this.cargoRow());
    this.form.controls.specialItems.clear();
    for (const item of enquiry.specialItems ?? []) this.form.controls.specialItems.push(this.specialRow(item.description, item.paid, item.salePriceUsd));
    this.loadingClientFromRecord = true;
    this.form.patchValue({
      branchId: enquiry.branchId,
      receivedAt: enquiry.receivedAt ? new Date(enquiry.receivedAt) : null,
      client: enquiry.client ? { id: enquiry.client.id, label: `${enquiry.client.name} (${enquiry.client.code})` } : null,
      clientContactId: enquiry.contact?.id ?? null,
      cargoTypes: enquiry.cargoTypes?.length ? enquiry.cargoTypes : enquiry.operationType ? [enquiry.operationType] : [],
      expectedDurationHours: enquiry.expectedDurationHours,
      sourceReference: enquiry.sourceReference ?? '',
      mother: vesselValue(enquiry.motherVessel),
      daughter: vesselValue(enquiry.daughterVessel),
    });
    this.loadingClientFromRecord = false;
    this.syncCargoTypesFromCargo();
    this.form.markAsPristine();
    this.applyAccess();
  }

  /** Read-only without write permission or once the enquiry left DRAFT/COSTING; the branch is fixed after creation. */
  private applyAccess(): void {
    if (!this.editable()) {
      this.form.disable({ emitEvent: false });
      return;
    }
    this.form.enable({ emitEvent: false });
    if (this.record()) {
      this.form.controls.branchId.disable({ emitEvent: false });
    }
  }

  private toRequest(): EnquiryRequest {
    const v = this.form.getRawValue();
    const vessel = (group: typeof v.mother) =>
      group.vessel ? { vesselId: group.vessel.id, etaLocal: toLocalDateTime(group.eta) } : null;
    return {
      branchId: v.branchId!,
      receivedAt: v.receivedAt?.toISOString() ?? null,
      clientId: v.client?.id ?? null,
      clientContactId: v.clientContactId,
      operationType: inferOperationType(v.cargoTypes), cargoTypes: v.cargoTypes,
      expectedDurationHours: v.expectedDurationHours === null ? null : String(v.expectedDurationHours),
      scopeOfServices: this.record()?.scopeOfServices ?? null,
      specialRequirements: this.record()?.specialRequirements ?? null,
      sourceReference: v.sourceReference.trim() || null,
      motherVessel: vessel(v.mother),
      daughterVessel: vessel(v.daughter),
      cargo: v.cargo
        .filter((line) => line.cargoGradeId || line.quantity != null || line.unitId)
        .map((line) => ({ cargoGradeId: line.cargoGradeId!, quantity: String(line.quantity), unitId: line.unitId! })),
      specialItems: v.specialItems.map(item => ({ description: item.description.trim(), paid: item.paid,
        salePriceUsd: item.paid && item.salePriceUsd != null ? String(item.salePriceUsd) : null })),
    };
  }

  private fail(problem: Problem): void {
    this.saving.set(false);
    const mapped = { ...problem, errors: problem.errors?.map((e) => ({ ...e, field: toControlPath(e.field) })) };
    if (!applyServerErrors(this.form, mapped)) this.errorKey.set(problemKey(problem));
    else this.errorKey.set('errors.codes.VALIDATION_FAILED');
  }

  private checkDuplicates([mother, daughter]: (string | undefined)[]): void {
    if (!mother || !daughter) {
      this.duplicates.set([]);
      return;
    }
    this.service.duplicates(mother, daughter, this.record()?.id ?? null)
      .pipe(catchError(() => of([] as EnquirySummary[])), takeUntilDestroyed(this.destroyRef))
      .subscribe((found) => this.duplicates.set(found));
  }

  private vesselGroup(): VesselGroup {
    return new FormGroup({ vessel: new FormControl<Picked | null>(null), eta: new FormControl<Date | null>(null) });
  }

  private cargoRow(gradeId: string | null = null, quantity: number | null = null, unitId: string | null = null): CargoRow {
    const row: CargoRow = new FormGroup({
      cargoGradeId: new FormControl<string | null>(gradeId),
      quantity: new FormControl<number | null>(quantity),
      unitId: new FormControl<string | null>(unitId),
    });
    const syncCargoRules = () => {
      const hasAny = !!(row.controls.cargoGradeId.value || row.controls.quantity.value != null || row.controls.unitId.value);
      row.controls.cargoGradeId.setValidators(hasAny ? Validators.required : []);
      row.controls.quantity.setValidators(hasAny ? [Validators.required, Validators.min(0.001)] : [Validators.min(0.001)]);
      row.controls.unitId.setValidators(hasAny ? Validators.required : []);
      row.controls.cargoGradeId.updateValueAndValidity({ emitEvent: false });
      row.controls.quantity.updateValueAndValidity({ emitEvent: false });
      row.controls.unitId.updateValueAndValidity({ emitEvent: false });
    };
    syncCargoRules();
    // Pre-select the grade's default unit when none is chosen yet.
    row.controls.cargoGradeId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((id) => {
      const unit = this.grades().find((g) => g.value === id)?.meta?.['defaultUnitId'];
      if (unit && !row.controls.unitId.value) row.controls.unitId.setValue(unit);
      this.syncCargoTypesFromCargo();
    });
    row.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(syncCargoRules);
    return row;
  }

  private specialRow(description = '', paid = false, salePriceUsd: number | null = null): SpecialRow {
    const row = new FormGroup({
      description: new FormControl(description, { nonNullable: true, validators: [Validators.required, Validators.maxLength(300)] }),
      paid: new FormControl(paid, { nonNullable: true }),
      salePriceUsd: new FormControl<number | null>(salePriceUsd),
    });
    const setPriceRule = (required: boolean) => {
      row.controls.salePriceUsd.setValidators(required ? [Validators.required, Validators.min(0.0001)] : []);
      row.controls.salePriceUsd.updateValueAndValidity({ emitEvent: false });
    };
    setPriceRule(paid);
    row.controls.paid.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(setPriceRule);
    return row;
  }

  private syncCargoTypesFromCargo(): void {
    const control = this.form.controls.cargoTypes;
    if (control.value.join('|') !== this.autoCargoTypes.join('|')) return;

    const selectedCategories = this.cargoRows.flatMap((row) => {
      const gradeId = row.controls.cargoGradeId.value;
      return gradeId ? [this.grades().find((grade) => grade.value === gradeId)?.meta?.['category']] : [];
    });
    const inferred = [...new Set(selectedCategories.filter((code): code is string => !!code && OPERATION_TYPES.includes(code)))];
    if (inferred.some((code) => !this.operationOptions().some((option) => option.value === code))) return;
    if (control.value.join('|') === inferred.join('|')) return;

    this.autoCargoTypes = inferred;
    this.settingAutoCargoTypes = true;
    control.setValue(inferred);
    this.settingAutoCargoTypes = false;
  }
}

function vesselValue(vessel: EnquiryDetail['motherVessel']): { vessel: Picked | null; eta: Date | null } {
  if (!vessel) return { vessel: null, eta: null };
  return {
    vessel: { id: vessel.vesselId, label: `${vessel.name ?? ''} · IMO ${vessel.imo ?? ''}` },
    eta: fromLocalDateTime(vessel.etaLocal),
  };
}

const toPicked = (option: LookupOption): Picked => ({ id: option.value, label: option.label });

export function inferOperationType(categories: (string | null | undefined)[]): OperationType | null {
  if (categories.length === 0 || categories.some((c) => !c || !OPERATION_TYPES.includes(c as OperationType))) return null;
  const selected = [...new Set(categories as OperationType[])];
  return selected.length === 1 ? selected[0] : 'OTHER';
}

/** Server field paths (`motherVessel.vesselId`, `cargo[1].unitId`, `clientId`) → form control paths. */
export function toControlPath(field: string): string {
  return field
    .replace(/^clientId$/, 'client')
    .replace(/^motherVessel\.vesselId$/, 'mother.vessel')
    .replace(/^daughterVessel\.vesselId$/, 'daughter.vessel')
    .replace(/^motherVessel\.etaTimezone$/, 'mother.eta')
    .replace(/^daughterVessel\.etaTimezone$/, 'daughter.eta')
    .replace(/\[(\d+)]/g, '.$1');
}
