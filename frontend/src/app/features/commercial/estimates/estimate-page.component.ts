import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { catchError, of, startWith } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { EmptyStateComponent } from '../../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { MoneyPipe } from '../../../shared/pipes/money.pipe';
import { EnquiryTabsComponent } from '../enquiries/enquiry-tabs.component';
import { EnquiryDetail, EnquiryService } from '../enquiries/enquiry.service';
import { EquipmentOption, Estimate, EstimateRequest, EstimateService, EstimateSummary, LineKind, equipmentRate, estimateTotals, filterEstimateCostHeads, lineAmount } from './estimate.service';

type LineRow = FormGroup<{
  equipmentModelId: FormControl<string | null>;
  kind: FormControl<LineKind>;
  costHeadId: FormControl<string | null>;
  description: FormControl<string>;
  quantity: FormControl<number | null>;
  unitPrice: FormControl<number | null>;
  extraHours: FormControl<boolean>;
}>;

const MAX_LINES = 100;

/**
 * Cost estimate of an enquiry (FR-J02): versions, cost and revenue lines by cost head, live exact totals and margin.
 * Remains editable across every stage of the operation.
 */
@Component({
  selector: 's2s-estimate-page',
  imports: [
    FormsModule, ReactiveFormsModule, TranslatePipe, Button, InputNumber, Message, Select, Textarea,
    EmptyStateComponent, EnquiryTabsComponent, FormFieldComponent, PageHeaderComponent, StatusTagComponent, MoneyPipe,
  ],
  templateUrl: './estimate-page.component.html',
  styleUrl: './estimate-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EstimatePageComponent {
  private readonly service = inject(EstimateService);
  private readonly enquiries = inject(EnquiryService);
  private readonly lookups = inject(LookupService);
  private readonly auth = inject(AuthService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Enquiry id (route parameter). */
  readonly id = input.required<string>();
  readonly embedded = input(false);
  readonly refreshKey = input(0);
  readonly selectedEstimateIdChange = output<string | null>();
  readonly saved = output<void>();

  protected readonly enquiry = signal<EnquiryDetail | null>(null);
  protected readonly versions = signal<EstimateSummary[]>([]);
  protected readonly estimate = signal<Estimate | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly equipmentLoadError = signal(false);

  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.estimateWrite));
  protected readonly editable = computed(() => this.canWrite());
  protected readonly maxLines = MAX_LINES;
  protected readonly lineKinds: LineKind[] = ['COST', 'REVENUE'];

  protected readonly currencies = toSignal(this.lookups.currencies().pipe(catchError(() => of([] as LookupOption[]))), { initialValue: [] });
  private readonly allTariffs = toSignal(this.lookups.tariffs().pipe(catchError(() => of([] as LookupOption[]))), { initialValue: [] });
  private readonly costHeads = toSignal(this.lookups.costHeads().pipe(catchError(() => of([] as LookupOption[]))), { initialValue: [] });
  private readonly hasSpecialRequirement = computed(() => {
    const enquiry = this.enquiry();
    return (enquiry?.specialItems?.length ?? 0) > 0 || !!enquiry?.specialRequirements?.trim();
  });
  protected readonly headsFor = computed(() => {
    const used = new Set((this.estimate()?.lines ?? []).map((l) => l.costHeadId));
    const hasSpecial = this.hasSpecialRequirement();
    return {
      COST: filterEstimateCostHeads(this.costHeads(), 'COST', hasSpecial, used),
      REVENUE: filterEstimateCostHeads(this.costHeads(), 'REVENUE', hasSpecial, used),
    };
  });

  protected readonly equipment = signal<EquipmentOption[]>([]);
  protected readonly equipmentOptions = computed(() => {
    this.language.current();
    return this.equipment().map((o) => ({ value: o.modelId,
      label: this.translate.instant('estimate.equipmentOption', { name: o.name, category: o.categoryLabel, available: o.available }) }));
  });

  protected readonly kindOptions = computed(() => {
    this.language.current();
    return (['COST', 'REVENUE'] as LineKind[]).map((value) => ({ value, label: this.translate.instant(`estimate.kind${value}`) }));
  });

  protected readonly versionOptions = computed(() => {
    this.language.current();
    return this.versions().map((v) => ({
      value: v.id,
      label: v.marginPercent == null
        ? this.translate.instant('estimate.versionOptionNoMargin', { no: v.versionNo, currency: v.currency })
        : this.translate.instant('estimate.versionOption', { no: v.versionNo, currency: v.currency, margin: `${v.marginPercent}%` }),
    }));
  });

  protected readonly form = new FormGroup({
    currency: new FormControl<string | null>(null, Validators.required),
    tariffId: new FormControl<string | null>(null),
    inrFxRate: new FormControl<number | null>(null, [Validators.min(0.0001)]),
    notes: new FormControl('', { nonNullable: true, validators: Validators.maxLength(2000) }),
    lines: new FormArray<LineRow>([]),
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly tariffOptions = computed(() => {
    this.formValue();
    const currency = this.form.controls.currency.value;
    return this.allTariffs().filter((t) => !currency || t.meta?.['currency'] === currency);
  });
  protected readonly selectedTariff = computed(() => {
    this.formValue();
    return this.allTariffs().find((option) => option.value === this.form.controls.tariffId.value);
  });
  protected readonly baseHours = computed(() => {
    const enquiryHours = this.enquiry()?.expectedDurationHours;
    if (enquiryHours != null) return enquiryHours;
    const tariffHours = this.selectedTariff()?.meta?.['baseHours'];
    return tariffHours ? Number(tariffHours) : null;
  });

  /** Exact live totals of what is on screen (big.js). */
  protected readonly totals = computed(() => {
    this.formValue();
    this.costHeads();
    return estimateTotals(this.rows.filter((row) => this.showLineQuantity(row) && this.completeLine(row)).map((row) => row.getRawValue()));
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.refreshKey();
      untracked(() => this.load(id));
    });
  }

  protected get rows(): LineRow[] {
    return this.form.controls.lines.controls;
  }

  protected hasLines(kind: LineKind): boolean { return this.rows.some((row) => row.controls.kind.value === kind); }

  protected isAdditionalTariffRevenueLine(row: LineRow): boolean {
    if (row.controls.kind.value !== 'REVENUE') return false;
    if (row.controls.extraHours.value) return true;
    const headCode = this.costHeads().find((head) => head.value === row.controls.costHeadId.value)?.meta?.['code'];
    return headCode === 'ADD-TARIFF';
  }

  protected showLineQuantity(row: LineRow): boolean {
    return !this.isAdditionalTariffRevenueLine(row) || row.controls.extraHours.value;
  }

  private completeLine(row: LineRow): boolean {
    return !!row.controls.costHeadId.value && row.controls.unitPrice.value != null &&
      (!this.showLineQuantity(row) || row.controls.quantity.value != null);
  }

  protected amountOf(row: LineRow) {
    return this.showLineQuantity(row) ? lineAmount(row.controls.quantity.value, row.controls.unitPrice.value) : lineAmount(0, 0);
  }

  protected extraHoursEnabled(): boolean { return this.rows.some((row) => row.controls.extraHours.value); }

  protected toggleRowExtraHours(row: LineRow, enabled: boolean): void {
    if (!this.isAdditionalTariffRevenueLine(row)) return;
    if (enabled && this.rows.some((candidate) => candidate !== row && candidate.controls.extraHours.value)) return;
    row.controls.extraHours.setValue(enabled);
    if (enabled) {
      const tariff = this.selectedTariff();
      if (tariff && row.controls.unitPrice.value == null) row.controls.unitPrice.setValue(Number(tariff.meta?.['extraHourRate']));
      const hours = this.baseHours();
      if (hours != null && !row.controls.description.value.trim()) row.controls.description.setValue(`Additional hours beyond ${hours} h`);
    }
  }

  protected tariffChanged(): void {
    const tariff = this.selectedTariff();
    if (!tariff) return;
    const row = this.rows.find((candidate) => this.isAdditionalTariffRevenueLine(candidate));
    if (row) {
      row.patchValue({
        unitPrice: Number(tariff.meta?.['extraHourRate']),
        ...(row.controls.extraHours.value ? { description: `Additional hours beyond ${this.baseHours() ?? tariff.meta?.['baseHours']} h` } : {}),
      });
    }
  }

  protected equipmentHasCostHead(row: LineRow): boolean {
    const id = this.equipment().find((o) => o.modelId === row.controls.equipmentModelId.value)?.costHeadId;
    return !!id && this.headsFor().COST.some((head) => head.value === id);
  }

  protected currencyChanged(currency: string): void {
    const tariff = this.form.controls.tariffId.value;
    if (tariff && !this.allTariffs().some((t) => t.value === tariff && t.meta?.['currency'] === currency)) {
      this.form.controls.tariffId.setValue(null);
    }
    if (currency !== 'USD') this.form.controls.inrFxRate.setValue(null);
    for (const row of this.rows) {
      const option = this.equipment().find((o) => o.modelId === row.controls.equipmentModelId.value);
      if (option) row.controls.unitPrice.setValue(equipmentRate(option, currency));
    }
  }

  protected addLine(kind: LineKind): void {
    if (this.form.controls.lines.length < MAX_LINES) this.form.controls.lines.push(this.row(kind));
  }

  protected removeLine(index: number): void {
    this.form.controls.lines.removeAt(index);
  }

  protected selectVersion(id: string): void {
    if (id && id !== this.estimate()?.id) this.open(id);
  }

  /** Starts the first estimate, or a new version copied from the one shown. */
  protected newVersion(): void {
    this.saving.set(true);
    this.errorKey.set(null);
    this.service.create(this.id(), this.estimate()?.id ?? null, 'USD').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.refreshVersions();
        this.reloadEnquiry();
        this.show(created);
      },
      error: (error: unknown) => this.fail(toProblem(error)),
    });
  }

  protected save(): void {
    const current = this.estimate();
    if (!current) return;
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      this.errorKey.set('errors.codes.VALIDATION_FAILED');
      return;
    }
    const v = this.form.getRawValue();
    const request: EstimateRequest = {
      currency: v.currency!,
      notes: v.notes.trim() || null,
      tariffId: v.tariffId,
      inrFxRate: v.currency === 'USD' ? v.inrFxRate : null,
      lines: this.rows.filter((row) => this.completeLine(row)).map((row) => {
        const line = row.getRawValue();
        const deferred = !this.showLineQuantity(row);
        return {
          kind: line.kind,
          costHeadId: line.costHeadId!,
          description: line.description.trim() || null,
          quantity: deferred ? '0' : String(line.quantity),
          unitPrice: String(line.unitPrice),
          equipmentModelId: line.equipmentModelId,
          extraHours: line.extraHours,
        };
      }),
    };
    this.saving.set(true);
    this.errorKey.set(null);
    this.service.update(current.id, current.version, request).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
        this.refreshVersions();
        this.show(saved);
        this.saved.emit();
      },
      error: (error: unknown) => this.fail(toProblem(error)),
    });
  }

  protected reload(): void {
    this.load(this.id());
  }

  private load(enquiryId: string): void {
    const selectedId = this.estimate()?.id;
    this.loading.set(true);
    this.errorKey.set(null);
    this.reloadEnquiry();
    this.service.list(enquiryId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (versions) => {
        this.versions.set(versions);
        if (versions.length) this.open(versions.find((version) => version.id === selectedId)?.id ?? versions[0].id);
        else { this.loading.set(false); this.selectedEstimateIdChange.emit(null); }
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  private reloadEnquiry(): void {
    this.enquiries.get(this.id()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (e) => this.enquiry.set(e), error: () => undefined });
  }

  private refreshVersions(): void {
    this.service.list(this.id()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (v) => this.versions.set(v), error: () => undefined });
  }

  private open(id: string): void {
    this.loading.set(true);
    this.service.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (estimate) => {
        this.loading.set(false);
        this.show(estimate);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  private show(estimate: Estimate): void {
    if (estimate.id !== this.estimate()?.id) {
      this.equipmentLoadError.set(false);
      this.service.equipmentOptions(estimate.id).pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (options) => this.equipment.set(options), error: () => { this.equipment.set([]); this.equipmentLoadError.set(true); } });
    }
    this.estimate.set(estimate);
    this.selectedEstimateIdChange.emit(estimate.id);
    const lines = this.form.controls.lines;
    lines.clear({ emitEvent: false });
    for (const line of estimate.lines) {
      const deferred = line.kind === 'REVENUE' && line.costHeadCode === 'ADD-TARIFF' && !line.extraHours;
      const created = this.row(line.kind, line.costHeadId, line.description ?? '',
        deferred ? null : line.quantity, line.unitPrice, line.equipmentModelId, line.extraHours);
      lines.push(created, { emitEvent: false });
    }
    this.form.patchValue({ currency: estimate.currency, tariffId: estimate.tariffId, inrFxRate: estimate.inrFxRate ?? null, notes: estimate.notes ?? '' });
    this.form.markAsPristine();
    if (this.editable()) this.form.enable({ emitEvent: false });
    else this.form.disable({ emitEvent: false });
    this.form.updateValueAndValidity();
  }

  private fail(problem: Problem): void {
    this.saving.set(false);
    const mapped = { ...problem, errors: problem.errors?.map((e) => ({ ...e, field: e.field.replace(/\[(\d+)]/g, '.$1') })) };
    if (!applyServerErrors(this.form, mapped)) this.errorKey.set(problemKey(problem));
    else this.errorKey.set('errors.codes.VALIDATION_FAILED');
  }

  private row(kind: LineKind, costHeadId: string | null = null, description = '', quantity: number | null = 1, unitPrice: number | null = null,
    equipmentModelId: string | null = null, extraHours = false): LineRow {
    const row: LineRow = new FormGroup({
      equipmentModelId: new FormControl<string | null>(equipmentModelId),
      kind: new FormControl<LineKind>(kind, { nonNullable: true }),
      costHeadId: new FormControl<string | null>(costHeadId),
      description: new FormControl(description, { nonNullable: true, validators: Validators.maxLength(300) }),
      quantity: new FormControl<number | null>(quantity, Validators.min(0.001)),
      unitPrice: new FormControl<number | null>(unitPrice, Validators.min(0)),
      extraHours: new FormControl(extraHours, { nonNullable: true }),
    });
    // A cost head that does not fit the new type is cleared.
    row.controls.kind.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((next) => {
      const head = row.controls.costHeadId.value;
      if (head && !this.headsFor()[next].some((h) => h.value === head)) row.controls.costHeadId.setValue(null);
      if (next !== 'REVENUE') {
        row.controls.extraHours.setValue(false, { emitEvent: false });
        if (row.controls.quantity.value == null) row.controls.quantity.setValue(1, { emitEvent: false });
      }
    });
    // Additional tariff revenue lines hide hours until Extra hours is checked, while keeping per-hour cost editable.
    row.controls.costHeadId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((headId) => {
      const isAddTariff = row.controls.kind.value === 'REVENUE' &&
        this.costHeads().find((head) => head.value === headId)?.meta?.['code'] === 'ADD-TARIFF';
      if (isAddTariff) {
        if (!row.controls.extraHours.value) {
          const tariffRate = this.selectedTariff()?.meta?.['extraHourRate'];
          row.patchValue({ quantity: null, unitPrice: row.controls.unitPrice.value ?? (tariffRate ? Number(tariffRate) : null) }, { emitEvent: false });
        }
      } else {
        row.controls.extraHours.setValue(false, { emitEvent: false });
        if (row.controls.quantity.value == null) row.controls.quantity.setValue(1, { emitEvent: false });
      }
    });
    // Equipment fills the line from the master: a cost line, its category cost head, its name and, in this currency only, its rate.
    row.controls.equipmentModelId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((id) => {
      const option = this.equipment().find((o) => o.modelId === id);
      if (!option) { row.patchValue({ description: '', costHeadId: null, unitPrice: null }); return; }
      row.patchValue({ kind: 'COST', description: option.name, costHeadId: option.costHeadId,
        unitPrice: equipmentRate(option, this.form.controls.currency.value) });
    });
    return row;
  }

}
