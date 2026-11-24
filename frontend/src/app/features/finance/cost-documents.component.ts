import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, debounceTime, startWith, switchMap } from 'rxjs';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { CostDocumentRow, FinanceListsService, formatMoney } from './finance.service';

const SIZE = 25;

/** Purchase orders or vendor bills across the jobs in scope (route data `kind`), each opening its job's finance page. */
@Component({
  selector: 's2s-cost-documents',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, InputText, Message, Select, EmptyStateComponent, PageHeaderComponent, StatusTagComponent],
  template: `
    <s2s-page-header [heading]="'finance.costDocsTitle.' + kind() | translate" [subtitle]="'finance.costDocsSubtitle.' + kind() | translate" />
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="cd-search">{{ 'finance.costSearch' | translate }}</label>
          <input pInputText id="cd-search" type="search" [ngModel]="query()" (ngModelChange)="search($event)" /></div>
        <div class="field"><label class="s2s-muted" for="cd-status">{{ 'finance.status' | translate }}</label>
          <p-select inputId="cd-status" [options]="statusOptions()" optionLabel="label" optionValue="value" [ngModel]="status()"
            (ngModelChange)="filter($event)" appendTo="body" /></div>
      </div>
      <ul class="items">
        @for (d of rows(); track d.id) {
          <li><a [routerLink]="['/jobs', d.jobId, 'finance']" [queryParams]="{ tab: 'expenses' }">
            <span class="line1"><strong>{{ d.number ?? d.reference }}</strong> <s2s-status-tag [status]="d.status" /></span>
            <span class="s2s-muted">{{ d.vendorName }} · {{ d.jobNumber }} · {{ d.documentDate }}@if (d.dueDate) { · {{ 'finance.due' | translate: { date: d.dueDate } }} }</span>
            <span>{{ d.currency }} {{ money(d.grossAmount) }}@if (d.currency !== d.jobCurrency) { · {{ d.jobCurrency }} {{ money(d.jobAmount) }} }</span>
          </a></li>
        } @empty {
          <li><s2s-empty-state messageKey="finance.noCostDocs" icon="pi pi-file" /></li>
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
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin-bottom: 0.75rem; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 12rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items a { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); color: inherit;
      text-decoration: none; min-height: 44px; overflow-wrap: anywhere; }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CostDocumentsComponent {
  private readonly service = inject(FinanceListsService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly load$ = new Subject<void>();

  /** `PO` or `BILL` (route data). */
  readonly kind = input<'PO' | 'BILL'>('BILL');

  protected readonly rows = signal<CostDocumentRow[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly query = signal('');
  protected readonly status = signal<string | null>(null);
  protected readonly error = signal(false);
  protected readonly pages = computed(() => Math.ceil(this.total() / SIZE));
  protected readonly statusOptions = computed(() => {
    this.language.current();
    const statuses = this.kind() === 'PO' ? ['DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED', 'REVERSED'] : ['DRAFT', 'SUBMITTED', 'APPROVED', 'REVERSED'];
    return [{ value: null as string | null, label: this.translate.instant('finance.allStatuses') },
      ...statuses.map((s) => ({ value: s as string | null, label: this.translate.instant(`status.${s}`) }))];
  });

  constructor() {
    this.load$.pipe(
      startWith(undefined),
      debounceTime(250),
      switchMap(() => this.service.costDocuments(this.kind(), this.status(), this.query(), this.page(), SIZE).pipe(
        catchError(() => { this.error.set(true); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      this.error.set(false);
      this.rows.set(p.items);
      this.total.set(p.totalElements);
    });
  }

  protected search(value: string): void { this.query.set(value); this.page.set(0); this.load$.next(); }
  protected filter(value: string | null): void { this.status.set(value); this.page.set(0); this.load$.next(); }
  protected go(page: number): void { this.page.set(page); this.load$.next(); }
  protected money(value: string | number): string { return formatMoney(value); }
}
