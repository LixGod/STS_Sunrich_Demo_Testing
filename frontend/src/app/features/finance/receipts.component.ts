import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, debounceTime, startWith, switchMap } from 'rxjs';
import { problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { FinanceService, InvoiceSummary, Money, Receipt, ReceiptParty, formatMoney, localToday, toBig } from './finance.service';

const SIZE = 25;
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Receipts from clients (FR-J11): recorded in the invoice currency, allocated to open issued invoices of the same client,
 * with any balance on account; a wrong receipt is reversed with a reason.
 */
@Component({
  selector: 's2s-receipts',
  imports: [
    FormsModule, ReactiveFormsModule, RouterLink, TranslatePipe, Button, Dialog, InputText, Message, Select, EmptyStateComponent, FormFieldComponent,
    PageHeaderComponent, ReasonDialogComponent, StatusTagComponent,
  ],
  template: `
    <s2s-page-header [heading]="'finance.receiptsTitle' | translate" [subtitle]="'finance.receiptsSubtitle' | translate">
      @if (canWrite()) { <p-button [label]="'finance.newReceipt' | translate" icon="pi pi-plus" (onClick)="openNew()" /> }
    </s2s-page-header>
    @if (errorKey(); as key) { <p-message severity="error" role="alert" [text]="key | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="rc-search">{{ 'finance.receiptSearch' | translate }}</label>
          <input pInputText id="rc-search" type="search" [ngModel]="query()" (ngModelChange)="search($event)" /></div>
        <div class="field"><label class="s2s-muted" for="rc-status">{{ 'finance.status' | translate }}</label>
          <p-select inputId="rc-status" [options]="statusOptions()" optionLabel="label" optionValue="value" [ngModel]="status()"
            (ngModelChange)="filter($event)" appendTo="body" /></div>
      </div>
      <ul class="items">
        @for (r of receipts(); track r.id) {
          <li class="receipt">
            <div class="line1"><strong>{{ r.currency }} {{ money(r.amount, r.minorUnits) }}</strong> <s2s-status-tag [status]="r.status" />
              <span class="s2s-muted">{{ r.receivedOn }} · {{ 'finance.method.' + r.method | translate }}@if (r.reference) { · {{ r.reference }} }</span></div>
            <div>{{ r.clientName }}</div>
            <div class="s2s-muted small">{{ 'finance.allocated' | translate }} {{ money(r.allocatedAmount, r.minorUnits) }} ·
              {{ 'finance.onAccount' | translate }} {{ money(r.onAccountAmount, r.minorUnits) }}</div>
            @if (r.allocations.length) {
              <ul class="allocations">
                @for (a of r.allocations; track a.id) {
                  <li [class.reversed]="a.reversed"><a [routerLink]="['/finance/invoices', a.invoiceId]">{{ a.invoiceNumber }}</a>: {{ money(a.amount, r.minorUnits) }}</li>
                }
              </ul>
            }
            @if (r.reversalReason) { <p class="s2s-muted small">{{ 'finance.reversed' | translate: { reason: r.reversalReason } }}</p> }
            @if (r.canWrite) {
              <div class="actions">
                @if (positive(r.onAccountAmount)) {
                  <p-button [label]="'finance.allocate' | translate" icon="pi pi-link" [outlined]="true" size="small" (onClick)="openAllocate(r)" />
                }
                <p-button [label]="'finance.reverse' | translate" icon="pi pi-undo" severity="danger" [text]="true" size="small" (onClick)="reversing.set(r)" />
              </div>
            }
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="finance.noReceipts" icon="pi pi-money-bill" /></li>
        }
      </ul>
      @if (pages() > 1) {
        <div class="pager">
          <p-button [label]="'finance.previous' | translate" icon="pi pi-chevron-left" [text]="true" [disabled]="page() === 0" (onClick)="go(page() - 1)" />
          <span class="s2s-muted">{{ 'finance.pageOf' | translate: { page: page() + 1, pages: pages() } }}</span>
          <p-button [label]="'finance.next' | translate" icon="pi pi-chevron-right" iconPos="right" [text]="true" [disabled]="page() + 1 >= pages()" (onClick)="go(page() + 1)" />
        </div>
      }
    </section>

    <p-dialog [header]="(allocating() ? 'finance.allocateTitle' : 'finance.newReceipt') | translate" [visible]="dialogOpen()" (visibleChange)="close($event)"
      [modal]="true" [draggable]="false" [style]="{ width: 'min(40rem, 96vw)' }">
      @if (dialogError(); as key) { <p-message severity="error" role="alert" [text]="key | translate" /> }
      <form [formGroup]="form" novalidate class="dialog-form" (ngSubmit)="confirm()">
        @if (!allocating()) {
          @if (parties().length === 0) { <p class="s2s-muted">{{ 'finance.noParties' | translate }}</p> }
          <s2s-form-field labelKey="finance.party" fieldId="rc-party" [control]="form.controls.party" [required]="true" hintKey="finance.choosePartyHint">
            <p-select inputId="rc-party" formControlName="party" [options]="partyOptions()" optionLabel="label" optionValue="value" appendTo="body"
              (onChange)="partyChanged()" />
          </s2s-form-field>
          <div class="grid">
            <s2s-form-field labelKey="finance.receivedOn" fieldId="rc-on" [control]="form.controls.receivedOn" [required]="true">
              <input pInputText id="rc-on" type="date" formControlName="receivedOn" [max]="today" />
            </s2s-form-field>
            <s2s-form-field labelKey="finance.receiptAmount" fieldId="rc-amount" [control]="form.controls.amount" [required]="true">
              <input pInputText id="rc-amount" formControlName="amount" inputmode="decimal" />
            </s2s-form-field>
            <s2s-form-field labelKey="finance.methodLabel" fieldId="rc-method" [control]="form.controls.method">
              <p-select inputId="rc-method" formControlName="method" [options]="methodOptions()" optionLabel="label" optionValue="value" appendTo="body" />
            </s2s-form-field>
            <s2s-form-field labelKey="finance.reference" fieldId="rc-ref" [control]="form.controls.reference">
              <input pInputText id="rc-ref" formControlName="reference" maxlength="100" />
            </s2s-form-field>
          </div>
        }
        <h3>{{ 'finance.allocations' | translate }}</h3>
        <div formArrayName="allocations" class="alloc-list">
          @for (inv of openInvoices(); track inv.id; let n = $index) {
            <div class="alloc">
              <div><strong>{{ inv.number }}</strong> <span class="s2s-muted">{{ inv.jobNumber }}</span><br />
                <span class="s2s-muted small">{{ 'finance.openOn' | translate: { amount: money(inv.outstandingAmount, inv.minorUnits) } }}
                  @if (inv.overdueDays) { · {{ 'finance.overdueDays' | translate: { days: inv.overdueDays } }} }</span></div>
              <div class="alloc-input">
                <label class="sr-only" [for]="'rc-al-' + n">{{ 'finance.allocation' | translate: { number: inv.number } }}</label>
                <input pInputText [id]="'rc-al-' + n" [formControlName]="n" inputmode="decimal" [attr.aria-label]="'finance.allocation' | translate: { number: inv.number }" />
                <p-button [label]="'finance.fillOpen' | translate" [text]="true" size="small" (onClick)="fill(n, inv)" />
              </div>
            </div>
          } @empty {
            <p class="s2s-muted">{{ 'finance.noOpenInvoices' | translate }}</p>
          }
        </div>
        <p class="summary" data-testid="receipt-summary">{{ 'finance.allocated' | translate }} {{ money(split().allocated, minor()) }} ·
          {{ 'finance.onAccount' | translate }} {{ money(split().onAccount, minor()) }}</p>
        @if (split().over) { <p-message severity="warn" [text]="'finance.overAllocated' | translate" /> }
      </form>
      <ng-template #footer>
        <p-button [label]="'common.cancel' | translate" [text]="true" severity="secondary" (onClick)="close(false)" />
        <p-button [label]="(allocating() ? 'finance.allocate' : 'finance.newReceipt') | translate" icon="pi pi-check" [loading]="saving()"
          [disabled]="split().over" (onClick)="confirm()" />
      </ng-template>
    </p-dialog>

    <s2s-reason-dialog [visible]="reversing() !== null" (visibleChange)="$event || reversing.set(null)" headerKey="finance.reverse" messageKey="finance.reverseHint"
      confirmKey="finance.reverse" [reasonRequired]="true" [saving]="saving()" (confirmed)="reverse($event)" />
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin-bottom: 0.75rem; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 12rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
    .items { list-style: none; margin: 0; padding: 0; }
    .receipt { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); overflow-wrap: anywhere; }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .small { font-size: 0.85rem; margin: 0; }
    .allocations { margin: 0.2rem 0 0; padding-left: 1.1rem; font-size: 0.9rem; }
    .allocations .reversed { text-decoration: line-through; color: var(--s2s-muted, #6b7280); }
    .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.25rem; }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
    .dialog-form { display: flex; flex-direction: column; gap: 0.25rem; }
    .grid { display: grid; gap: 0 1rem; }
    .grid > * { min-width: 0; }
    @media (min-width: 640px) { .grid { grid-template-columns: 1fr 1fr; } }
    h3 { font-size: 0.95rem; margin: 0.75rem 0 0.25rem; }
    .alloc-list { display: flex; flex-direction: column; gap: 0.5rem; }
    .alloc { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 0.5rem; padding: 0.5rem 0; border-top: 1px solid var(--s2s-border); }
    .alloc > * { min-width: 0; }
    .alloc-input { display: flex; gap: 0.25rem; align-items: center; flex: 0 1 16rem; }
    .alloc-input input { width: 100%; min-width: 0; }
    .summary { margin: 0.5rem 0 0; font-variant-numeric: tabular-nums; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
    :host ::ng-deep .dialog-form :is(.p-select, .p-inputtext) { width: 100%; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceiptsComponent {
  private readonly service = inject(FinanceService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly load$ = new Subject<void>();

  /** Invoice to record a receipt for (query parameter `invoice`, from the invoice page). */
  readonly invoice = input<string | undefined>(undefined);

  protected readonly today = localToday();
  protected readonly receipts = signal<Receipt[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly query = signal('');
  protected readonly status = signal<string | null>(null);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly dialogOpen = signal(false);
  protected readonly allocating = signal<Receipt | null>(null);
  protected readonly reversing = signal<Receipt | null>(null);
  protected readonly parties = signal<ReceiptParty[]>([]);
  protected readonly openInvoices = signal<InvoiceSummary[]>([]);
  private readonly changes = signal(0);

  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.finReceiptWrite));
  protected readonly pages = computed(() => Math.ceil(this.total() / SIZE));
  protected readonly statusOptions = computed(() => {
    this.language.current();
    return [{ value: null, label: this.translate.instant('finance.allReceipts') },
      ...['ACTIVE', 'REVERSED'].map((s) => ({ value: s, label: this.translate.instant(`status.${s}`) }))];
  });
  protected readonly methodOptions = computed(() => {
    this.language.current();
    return ['BANK_TRANSFER', 'OTHER'].map((value) => ({ value, label: this.translate.instant(`finance.method.${value}`) }));
  });
  protected readonly partyOptions = computed(() => this.parties().map((p, i) => ({
    value: i, label: `${p.clientName ?? ''} · ${p.currency} (${formatMoney(p.outstandingAmount, p.minorUnits)})`,
  })));

  protected readonly form = new FormGroup({
    party: new FormControl<number | null>(null, Validators.required),
    receivedOn: new FormControl(this.today, { nonNullable: true, validators: Validators.required }),
    amount: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(AMOUNT)] }),
    method: new FormControl<'BANK_TRANSFER' | 'OTHER'>('BANK_TRANSFER', { nonNullable: true }),
    reference: new FormControl('', { nonNullable: true, validators: Validators.maxLength(100) }),
    allocations: new FormArray<FormControl<string>>([]),
  });

  protected readonly minor = computed(() => {
    const r = this.allocating();
    if (r) return r.minorUnits;
    this.changes();
    const p = this.form.controls.party.value;
    return p === null ? 2 : this.parties()[p]?.minorUnits ?? 2;
  });

  /** Live split of the receipt into allocated and on account (exact, big.js). */
  protected readonly split = computed(() => {
    this.changes();
    const allocated = this.form.controls.allocations.getRawValue().reduce((s, v) => s.plus(toBig(v)), toBig(0));
    const available = this.allocating() ? toBig(this.allocating()!.onAccountAmount) : toBig(this.form.controls.amount.value);
    return { allocated, onAccount: available.minus(allocated), over: allocated.gt(available) };
  });

  constructor() {
    this.load$.pipe(
      startWith(undefined),
      debounceTime(250),
      switchMap(() => this.service.receipts(this.status(), this.query(), this.page(), SIZE).pipe(
        catchError(() => { this.errorKey.set('errors.loadFailed'); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      this.errorKey.set(null);
      this.receipts.set(p.items);
      this.total.set(p.totalElements);
    });
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
    effect(() => {
      const invoiceId = this.invoice();
      if (invoiceId && this.canWrite()) untracked(() => this.openFor(invoiceId));
    });
  }

  protected money(value: Money | ReturnType<typeof toBig>, minorUnits: number): string {
    return formatMoney(value, minorUnits);
  }

  protected positive(value: Money): boolean {
    return toBig(value).gt(0);
  }

  protected search(value: string): void {
    this.query.set(value);
    this.page.set(0);
    this.load$.next();
  }

  protected filter(value: string | null): void {
    this.status.set(value);
    this.page.set(0);
    this.load$.next();
  }

  protected go(page: number): void {
    this.page.set(page);
    this.load$.next();
  }

  protected openNew(preselect?: { clientId: string; currency: string; invoiceId: string }): void {
    this.allocating.set(null);
    this.dialogError.set(null);
    this.form.reset({ party: null, receivedOn: this.today, amount: '', method: 'BANK_TRANSFER', reference: '' });
    this.form.controls.party.enable();
    this.setInvoices([]);
    this.dialogOpen.set(true);
    this.service.parties().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (parties) => {
        this.parties.set(parties);
        if (!preselect) return;
        const index = parties.findIndex((p) => p.clientId === preselect.clientId && p.currency === preselect.currency);
        if (index >= 0) {
          this.form.controls.party.setValue(index);
          this.loadInvoices(parties[index], preselect.invoiceId);
        }
      },
      error: () => this.dialogError.set('errors.loadFailed'),
    });
  }

  protected openAllocate(receipt: Receipt): void {
    this.allocating.set(receipt);
    this.dialogError.set(null);
    this.setInvoices([]);
    this.dialogOpen.set(true);
    this.service.openInvoices(receipt.clientId, receipt.currency).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (list) => this.setInvoices(list), error: () => this.dialogError.set('errors.loadFailed') });
  }

  protected partyChanged(): void {
    const p = this.form.controls.party.value;
    if (p !== null && this.parties()[p]) this.loadInvoices(this.parties()[p]);
  }

  protected fill(index: number, invoice: InvoiceSummary): void {
    this.form.controls.allocations.at(index).setValue(String(invoice.outstandingAmount));
  }

  protected close(open: boolean): void {
    if (!open) {
      this.dialogOpen.set(false);
      this.allocating.set(null);
    }
  }

  protected confirm(): void {
    if (this.saving() || this.split().over) return;
    const allocations = this.openInvoices().map((inv, i) => ({ invoiceId: inv.id, amount: this.form.controls.allocations.at(i).value.trim() }))
      .filter((a) => a.amount !== '' && toBig(a.amount).gt(0));
    const receipt = this.allocating();
    if (receipt) {
      this.submit(this.service.allocate(receipt, allocations));
      return;
    }
    const p = this.form.controls.party.value;
    if (this.form.invalid || p === null) {
      this.form.markAllAsTouched();
      return;
    }
    const party = this.parties()[p];
    const v = this.form.getRawValue();
    this.submit(this.service.recordReceipt({
      clientId: party.clientId, currency: party.currency, receivedOn: v.receivedOn, amount: v.amount.trim(),
      method: v.method, reference: v.reference.trim() || null, allocations,
    }));
  }

  protected reverse(reason: string | null): void {
    const r = this.reversing();
    if (!r) return;
    this.saving.set(true);
    this.service.reverse(r, reason ?? '').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.reversing.set(null);
        this.load$.next();
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.reversing.set(null);
        this.errorKey.set(problemKey(toProblem(e)));
      },
    });
  }

  private submit(call: ReturnType<FinanceService['recordReceipt']>): void {
    this.saving.set(true);
    this.dialogError.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.close(false);
        this.load$.next();
      },
      error: (e: unknown) => {
        this.saving.set(false);
        const problem = toProblem(e);
        this.dialogError.set(applyServerErrors(this.form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
      },
    });
  }

  private openFor(invoiceId: string): void {
    this.service.invoice(invoiceId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (i) => this.openNew({ clientId: i.clientId, currency: i.currency, invoiceId: i.id }),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private loadInvoices(party: ReceiptParty, preset?: string): void {
    this.service.openInvoices(party.clientId, party.currency).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => {
        this.setInvoices(list);
        const index = preset ? list.findIndex((i) => i.id === preset) : -1;
        if (index >= 0) {
          this.fill(index, list[index]);
          if (!this.form.controls.amount.value) this.form.controls.amount.setValue(String(list[index].outstandingAmount));
        }
      },
      error: () => this.dialogError.set('errors.loadFailed'),
    });
  }

  private setInvoices(list: InvoiceSummary[]): void {
    const array = this.form.controls.allocations;
    array.clear({ emitEvent: false });
    list.forEach(() => array.push(new FormControl('', { nonNullable: true, validators: Validators.pattern(AMOUNT) }), { emitEvent: false }));
    this.openInvoices.set(list);
    this.changes.update((n) => n + 1);
  }
}
