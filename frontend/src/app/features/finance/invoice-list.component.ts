import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, debounceTime, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { DocType, FinanceService, InvoiceFilters, InvoiceStatus, InvoiceSummary, formatMoney } from './finance.service';

const SIZE = 25;

/** Invoices and credit notes in the user's scope, filtered by status, type and overdue, newest first. */
@Component({
  selector: 's2s-invoice-list',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, Checkbox, InputText, Message, Select, EmptyStateComponent, PageHeaderComponent, StatusTagComponent],
  template: `
    <s2s-page-header [heading]="'finance.listTitle' | translate" [subtitle]="'finance.listSubtitle' | translate" />
    @if (canCreate()) {
      <a class="new-invoice" [routerLink]="['/jobs']" [queryParams]="{ invoice: '1' }">
        <i class="pi pi-file-plus" aria-hidden="true"></i> {{ 'finance.newInvoice' | translate }}
      </a>
    }
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="inv-search">{{ 'finance.search' | translate }}</label>
          <input pInputText id="inv-search" type="search" [ngModel]="filters().q" (ngModelChange)="set({ q: $event })" /></div>
        <div class="field"><label class="s2s-muted" for="inv-status">{{ 'finance.status' | translate }}</label>
          <p-select inputId="inv-status" [options]="statusOptions()" optionLabel="label" optionValue="value" [ngModel]="filters().status ?? null"
            (ngModelChange)="set({ status: $event })" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="inv-type">{{ 'finance.type' | translate }}</label>
          <p-select inputId="inv-type" [options]="typeOptions()" optionLabel="label" optionValue="value" [ngModel]="filters().docType ?? null"
            (ngModelChange)="set({ docType: $event })" appendTo="body" /></div>
        <span class="check"><p-checkbox inputId="inv-overdue" [binary]="true" [ngModel]="filters().overdue" (ngModelChange)="set({ overdue: $event })" />
          <label for="inv-overdue">{{ 'finance.overdueOnly' | translate }}</label></span>
      </div>
      <ul class="items">
        @for (i of items(); track i.id) {
          <li>
            <a [routerLink]="['/finance/invoices', i.id]">
              <span class="line1"><strong>{{ i.number ?? ('finance.draftNumber' | translate) }}</strong> <s2s-status-tag [status]="i.status" />
                @if (i.docType === 'CREDIT_NOTE') { <span class="s2s-muted">{{ 'finance.docType.CREDIT_NOTE' | translate }}</span> }</span>
              <span class="s2s-muted">{{ i.clientName }} · {{ i.jobNumber }} · {{ i.invoiceDate }}</span>
              <span>{{ i.currency }} {{ money(i.grossAmount, i.minorUnits) }}
                @if (i.status === 'ISSUED' && i.docType === 'INVOICE') { · {{ 'finance.outstanding' | translate: { amount: money(i.outstandingAmount, i.minorUnits) } }} }
                @if (i.overdueDays) { · <span class="overdue">{{ 'finance.overdueDays' | translate: { days: i.overdueDays } }}</span> }</span>
            </a>
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="finance.noInvoices" icon="pi pi-wallet" /></li>
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
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .new-invoice { align-self: flex-start; display: inline-flex; align-items: center; gap: 0.5rem; min-height: 44px;
      padding: 0.65rem 1rem; border-radius: var(--s2s-radius); background: var(--s2s-brand); color: #fff;
      font-weight: 600; text-decoration: none; }
    .new-invoice:hover, .new-invoice:focus-visible { background: var(--s2s-brand-dark); }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin-bottom: 0.75rem; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 12rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
    .check { display: inline-flex; gap: 0.5rem; align-items: center; min-height: 44px; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items a { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); color: inherit;
      text-decoration: none; min-height: 44px; overflow-wrap: anywhere; }
    .items a:hover strong, .items a:focus-visible strong { color: var(--p-primary-color); }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .overdue { color: var(--p-red-600, #dc2626); }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoiceListComponent {
  private readonly service = inject(FinanceService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly load$ = new Subject<void>();

  protected readonly filters = signal<InvoiceFilters>({ q: '', status: null, docType: null, overdue: false });
  protected readonly page = signal(0);
  protected readonly items = signal<InvoiceSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly error = signal(false);
  protected readonly canCreate = computed(() => this.auth.hasPermission(Permissions.finInvoiceWrite));
  protected readonly pages = computed(() => Math.ceil(this.total() / SIZE));
  protected readonly statusOptions = computed(() => {
    this.language.current();
    const statuses: InvoiceStatus[] = ['DRAFT', 'SUBMITTED', 'APPROVED', 'ISSUED'];
    return [{ value: null, label: this.translate.instant('finance.allStatuses') }, ...statuses.map((s) => ({ value: s, label: this.translate.instant(`status.${s}`) }))];
  });
  protected readonly typeOptions = computed(() => {
    this.language.current();
    const types: DocType[] = ['INVOICE', 'CREDIT_NOTE'];
    return [{ value: null, label: this.translate.instant('finance.allTypes') }, ...types.map((t) => ({ value: t, label: this.translate.instant(`finance.docType.${t}`) }))];
  });

  constructor() {
    this.load$.pipe(
      startWith(undefined),
      debounceTime(250),
      switchMap(() => this.service.invoices(this.filters(), this.page(), SIZE).pipe(
        catchError(() => { this.error.set(true); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      this.error.set(false);
      this.items.set(p.items);
      this.total.set(p.totalElements);
    });
  }

  protected set(change: Partial<InvoiceFilters>): void {
    this.filters.update((f) => ({ ...f, ...change }));
    this.page.set(0);
    this.load$.next();
  }

  protected go(page: number): void {
    this.page.set(page);
    this.load$.next();
  }

  protected money(value: string | number, minorUnits: number): string {
    return formatMoney(value, minorUnits);
  }
}
