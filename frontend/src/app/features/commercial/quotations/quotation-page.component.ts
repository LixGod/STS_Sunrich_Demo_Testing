import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import Big from 'big.js';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { InputNumber } from 'primeng/inputnumber';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { Observable, catchError, forkJoin, of, switchMap } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../../core/api/api.models';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { DateTimePickerComponent } from '../../../shared/components/date-time-picker/date-time-picker.component';
import { EmptyStateComponent } from '../../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { MoneyPipe } from '../../../shared/pipes/money.pipe';
import { UtcDateTimePipe } from '../../../shared/pipes/utc-datetime.pipe';
import { EnquiryTabsComponent } from '../enquiries/enquiry-tabs.component';
import { EnquiryDetail, EnquiryService, newIdempotencyKey } from '../enquiries/enquiry.service';
import { EstimateService, EstimateSummary } from '../estimates/estimate.service';
import { IssueChannel, QuotationOverview, QuotationService, QuotationTerms, QuotationVersion, addWorkingDays } from './quotation.service';

type OpenDialog = 'terms' | 'issue' | 'accept' | 'ownerReject' | null;

/**
 * Quotation of an enquiry (FR-J03): versions prepared from an estimate,
 * issue with a frozen PDF, and owner acceptance, which creates the STS job (UAT-03) with the tariff frozen (AR-02).
 */
@Component({
  selector: 's2s-quotation-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Dialog, InputText, InputNumber, Message, Select, Textarea,
    DateTimePickerComponent, EmptyStateComponent, EnquiryTabsComponent, FormFieldComponent, PageHeaderComponent,
    StatusTagComponent, MoneyPipe, UtcDateTimePipe,
  ],
  templateUrl: './quotation-page.component.html',
  styleUrl: './quotation-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuotationPageComponent {
  private readonly service = inject(QuotationService);
  private readonly estimates = inject(EstimateService);
  private readonly enquiries = inject(EnquiryService);
  private readonly lookups = inject(LookupService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Enquiry id (route parameter). */
  readonly id = input.required<string>();
  readonly embedded = input(false);
  readonly estimateId = input<string | null>(null);
  readonly refreshKey = input(0);

  protected readonly overview = signal<QuotationOverview | null>(null);
  protected readonly enquiry = signal<EnquiryDetail | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly estimateList = signal<EstimateSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly tariffsLoading = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  /** True while the terms dialog prepares a new version (otherwise it edits the selected draft). */
  protected readonly creating = signal(false);

  /** One key per acceptance dialog, so a double click or retry cannot create two jobs. */
  private acceptKey = newIdempotencyKey();
  private loadedEnquiryId: string | null = null;

  protected readonly matchingVersions = computed(() => {
    const versions = this.overview()?.versions ?? [];
    const estimateId = this.estimateId();
    return estimateId ? versions.filter((version) => version.estimateId === estimateId) : versions;
  });
  protected readonly selected = computed(() => {
    const versions = this.matchingVersions();
    return versions.find((v) => v.id === this.selectedId()) ?? (versions.length ? versions[0] : null);
  });

  protected readonly previewPages = signal<number[]>([]);
  protected readonly previewError = signal(false);

  protected readonly can = computed(() => new Set(this.selected()?.allowedActions ?? []));

  protected readonly termsForm = new FormGroup({
    estimateId: new FormControl<string | null>(null),
    validityDays: new FormControl<number | null>(5, [Validators.required, Validators.min(1), Validators.max(60)]),
    tariffId: new FormControl<string | null>(null),
    inrFxRate: new FormControl<number | null>(null, [Validators.min(0.0001)]),
    paymentTerms: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
    advanceReceived: new FormControl<number | null>(null, Validators.min(0)),
    advanceGiven: new FormControl<number | null>(null, Validators.min(0)),
    termsText: new FormControl('', { nonNullable: true, validators: Validators.maxLength(8000) }),
    scopeOfServices: new FormControl('', { nonNullable: true, validators: Validators.maxLength(8000) }),
  });

  protected readonly issueForm = new FormGroup({
    issuedTo: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(300)] }),
    channel: new FormControl<IssueChannel>('EMAIL', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly acceptForm = new FormGroup({
    acceptedAt: new FormControl<Date | null>(null, Validators.required),
    acceptedByName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    jobNumber: new FormControl('', { nonNullable: true, validators: Validators.maxLength(40) }),
    paymentTerms: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
    specialConditions: new FormControl('', { nonNullable: true, validators: Validators.maxLength(4000) }),
  });

  protected readonly advanceForm = new FormGroup({
    amount: new FormControl<number | null>(null, [Validators.required, Validators.min(0.01)]),
    note: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
  });

  protected readonly estimateOptions = computed(() => {
    this.language.current();
    return this.estimateList().map((e) => ({
      value: e.id,
      label: this.translate.instant('quotation.estimateOption', { no: e.versionNo, currency: e.currency, revenue: e.totalRevenue }),
      currency: e.currency,
    }));
  });

  /** The currency the quotation will be in: the selected draft's, or the chosen estimate's for a new version. */
  private readonly selectedEstimateId = toSignal(this.termsForm.controls.estimateId.valueChanges, { initialValue: null });
  protected readonly termsCurrency = computed(() => this.creating()
    ? this.estimateList().find((e) => e.id === this.selectedEstimateId())?.currency ?? null
    : this.selected()?.currency ?? null);

  private readonly allTariffs = signal<LookupOption[]>([]);
  protected readonly tariffOptions = computed(() => this.allTariffs().filter((t) => t.meta?.['currency'] === this.termsCurrency()));

  protected readonly channelOptions = computed(() => {
    this.language.current();
    return (['EMAIL', 'OTHER'] as IssueChannel[]).map((value) => ({ value, label: this.translate.instant(`quotation.channel${value}`) }));
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.refreshKey();
      untracked(() => this.load(id));
    });
    effect((onCleanup) => {
      const version = this.embedded() ? this.selected() : null;
      this.previewPages.set([]);
      this.previewError.set(false);
      if (!version) return;
      const request = this.service.previewPages(version).subscribe({
        next: ({ pages }) => this.previewPages.set(Array.from({ length: pages }, (_, i) => i + 1)),
        error: () => this.previewError.set(true),
      });
      onCleanup(() => request.unsubscribe());
    });
  }

  protected select(version: QuotationVersion): void {
    this.selectedId.set(version.id);
  }

  protected pdfUrl(version: QuotationVersion): string {
    return this.service.pdfUrl(version);
  }

  protected previewPageUrl(version: QuotationVersion, page: number): string {
    return this.service.previewPageUrl(version, page);
  }

  protected gst(amount: number): number {
    return new Big(amount).times('0.18').round(2, Big.roundHalfUp).toNumber();
  }

  protected openTerms(create: boolean): void {
    if (this.tariffsLoading()) return;
    const version = this.selected();
    this.creating.set(create);
    this.dialogError.set(null);
    this.errorKey.set(null);
    this.termsForm.reset();
    if (create) {
      this.termsForm.patchValue({ estimateId: null, validityDays: 5, tariffId: null,
        paymentTerms: version?.paymentTerms ?? '', advanceReceived: version?.advanceReceived ?? null,
        advanceGiven: version?.advanceGiven ?? null, termsText: version?.termsText ?? '',
        scopeOfServices: version?.scopeOfServices ?? '' });
      this.termsForm.controls.estimateId.setValidators(Validators.required);
    } else if (version) {
      this.termsForm.patchValue({ estimateId: version.estimateId, validityDays: workingDaysUntil(version.validUntil), tariffId: version.tariff?.id ?? null,
        inrFxRate: version.inrFxRate ?? null, paymentTerms: version.paymentTerms ?? '', advanceReceived: version.advanceReceived ?? null,
        advanceGiven: version.advanceGiven ?? null, termsText: version.termsText ?? '',
        scopeOfServices: version.scopeOfServices ?? '' });
      this.termsForm.controls.estimateId.clearValidators();
    }
    this.termsForm.controls.estimateId.updateValueAndValidity();
    this.tariffsLoading.set(true);
    const enquiry = this.enquiry();
    (enquiry ? of(enquiry) : this.enquiries.get(this.id())).pipe(
      switchMap((current) => {
        this.enquiry.set(current);
        if (create && !version?.scopeOfServices) this.termsForm.controls.scopeOfServices.setValue(current.scopeOfServices ?? '');
        return forkJoin({
          estimates: this.estimates.list(this.id()),
          tariffs: this.lookups.tariffs(),
        });
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: ({ estimates, tariffs }) => {
        this.tariffsLoading.set(false);
        this.estimateList.set(estimates);
        if (create) {
          const chosenId = estimates.some((estimate) => estimate.id === this.estimateId()) ? this.estimateId() : estimates[0]?.id ?? null;
          this.termsForm.controls.estimateId.setValue(chosenId);
          this.termsForm.controls.inrFxRate.setValue(estimates.find((estimate) => estimate.id === chosenId)?.inrFxRate ?? null);
        }
        this.allTariffs.set(tariffs);
        this.syncTariff();
        this.dialog.set('terms');
      },
      error: () => {
        this.tariffsLoading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  protected syncTariff(): void {
    const options = this.tariffOptions();
    if (!options.some((option) => option.value === this.termsForm.controls.tariffId.value)) {
      this.termsForm.controls.tariffId.setValue(options.length === 1 ? options[0].value : null);
    }
    if (this.creating()) {
      const selected = this.estimateList().find((estimate) => estimate.id === this.termsForm.controls.estimateId.value);
      this.termsForm.controls.inrFxRate.setValue(selected?.currency === 'USD' ? selected.inrFxRate ?? null : null);
    }
  }

  protected saveTerms(): void {
    if (this.termsForm.invalid || this.saving()) {
      this.termsForm.markAllAsTouched();
      return;
    }
    const v = this.termsForm.getRawValue();
    const terms: QuotationTerms = {
      validUntil: addWorkingDays(new Date(), v.validityDays!),
      tariffId: v.tariffId,
      inrFxRate: this.termsCurrency() === 'USD' ? v.inrFxRate : null,
      paymentTerms: v.paymentTerms.trim() || null,
      advanceReceived: v.advanceReceived,
      advanceGiven: v.advanceGiven,
      termsText: v.termsText.trim() || null,
      scopeOfServices: v.scopeOfServices.trim() || null,
    };
    const version = this.selected();
    const call = this.creating() || !version
      ? this.service.createVersion(this.id(), v.estimateId!, terms)
      : this.service.update(version, terms);
    this.run(call, this.termsForm, (result) => {
      if (this.creating()) this.selectedId.set(result.versions[0]?.id ?? null);
    });
  }

  protected openIssue(): void {
    this.dialogError.set(null);
    this.issueForm.reset({ issuedTo: '', channel: 'EMAIL', note: '' });
    this.dialog.set('issue');
  }

  protected saveIssue(): void {
    const version = this.selected();
    if (!version || this.issueForm.invalid || this.saving()) {
      this.issueForm.markAllAsTouched();
      return;
    }
    const v = this.issueForm.getRawValue();
    this.run(this.service.issue(version, v.issuedTo.trim(), v.channel, v.note.trim() || null), this.issueForm);
  }

  protected openAccept(): void {
    const version = this.selected();
    this.dialogError.set(null);
    this.acceptKey = newIdempotencyKey();
    this.acceptForm.reset({ acceptedAt: new Date(), acceptedByName: '', jobNumber: '', paymentTerms: version?.paymentTerms ?? '',
      specialConditions: '' });
    this.dialog.set('accept');
  }

  protected saveAccept(): void {
    const version = this.selected();
    if (!version || this.acceptForm.invalid || this.saving()) {
      this.acceptForm.markAllAsTouched();
      return;
    }
    const v = this.acceptForm.getRawValue();
    this.run(this.service.accept(version, {
      acceptedAt: v.acceptedAt!.toISOString(),
      acceptedByName: v.acceptedByName.trim(),
      jobNumber: v.jobNumber.trim() || null,
      paymentTerms: v.paymentTerms.trim() || null,
      specialConditions: v.specialConditions.trim() || null,
    }, this.acceptKey), this.acceptForm, (result) => {
      if (result.job) {
        this.messages.add({ severity: 'success', summary: this.translate.instant('quotation.jobCreated', { number: result.job.jobNumber }) });
      }
    });
  }

  protected rejectByOwner(): void {
    const version = this.selected();
    if (version) this.run(this.service.rejectByOwner(version));
  }

  protected fillRemainingAdvance(): void {
    const remaining = this.selected()?.advanceRemaining;
    if (remaining && remaining > 0) this.advanceForm.controls.amount.setValue(remaining);
  }

  protected recordAdvance(): void {
    const version = this.selected();
    if (!version || this.saving()) return;
    const amount = this.advanceForm.controls.amount.value;
    if (this.advanceForm.invalid || amount == null || amount <= 0) {
      this.advanceForm.markAllAsTouched();
      return;
    }
    if (amount > version.advanceRemaining) {
      this.advanceForm.controls.amount.setErrors({ max: { max: version.advanceRemaining, actual: amount } });
      this.advanceForm.markAllAsTouched();
      return;
    }
    const note = this.advanceForm.controls.note.value.trim() || null;
    this.run(this.service.recordAdvance(version, amount, note), this.advanceForm, () => {
      this.advanceForm.reset({ amount: null, note: '' });
      this.messages.add({ severity: 'success', summary: this.translate.instant('quotation.advanceRecorded') });
    });
  }

  protected closeDialog(open: boolean): void {
    if (!open) this.dialog.set(null);
  }

  protected reload(): void {
    this.load(this.id());
  }

  private load(enquiryId: string): void {
    this.loading.set(true);
    this.errorKey.set(null);
    if (enquiryId !== this.loadedEnquiryId) {
      this.loadedEnquiryId = enquiryId;
      this.overview.set(null);
      this.enquiry.set(null);
      this.estimateList.set([]);
    }
    this.service.overview(enquiryId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => {
        this.loading.set(false);
        this.overview.set(overview);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
    this.estimates.list(enquiryId).pipe(catchError(() => of([] as EstimateSummary[])), takeUntilDestroyed(this.destroyRef))
      .subscribe((list) => this.estimateList.set(list));
    this.enquiries.get(enquiryId).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (enquiry) => this.enquiry.set(enquiry), error: () => undefined });
  }

  /** Runs an action, redraws from the returned overview, and keeps a dialog open with its error if it fails. */
  private run(call: Observable<QuotationOverview>, form?: FormGroup, after?: (result: QuotationOverview) => void): void {
    this.saving.set(true);
    this.errorKey.set(null);
    this.dialogError.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.overview.set(result);
        this.dialog.set(null);
        after?.(result);
        this.estimates.list(this.id()).pipe(catchError(() => of([] as EstimateSummary[])), takeUntilDestroyed(this.destroyRef))
          .subscribe((list) => this.estimateList.set(list));
      },
      error: (error: unknown) => this.fail(toProblem(error), form),
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    const key = problemKey(problem);
    if (form && applyServerErrors(form, problem)) {
      if (this.dialog()) this.dialogError.set('errors.codes.VALIDATION_FAILED');
      else this.errorKey.set('errors.codes.VALIDATION_FAILED');
    } else if (this.dialog()) {
      this.dialogError.set(key);
    } else {
      this.dialog.set(null);
      this.errorKey.set(key);
    }
  }
}

/** `YYYY-MM-DD` of a date picker value, as picked (no time zone conversion). */
function workingDaysUntil(value: string): number {
  const [y, m, d] = value.split('-').map(Number);
  const target = new Date(y, m - 1, d);
  const day = new Date();
  day.setHours(0, 0, 0, 0);
  let days = 0;
  while (day < target && days < 60) {
    day.setDate(day.getDate() + 1);
    if (day.getDay() !== 0 && day.getDay() !== 6) days++;
  }
  return Math.max(1, days);
}
