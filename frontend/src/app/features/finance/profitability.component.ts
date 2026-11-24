import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, startWith, switchMap } from 'rxjs';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { FinanceListsService, ProfitabilityFilters, ProfitabilityRow, formatMoney, toBig } from './finance.service';

const SIZE = 25;

/** Income, actual cost and margin per job (each in its own currency), with an audited Excel export. */
@Component({
  selector: 's2s-profitability',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, InputText, Message, Select, EmptyStateComponent, PageHeaderComponent, StatusTagComponent],
  template: `
    <s2s-page-header [heading]="'finance.profitTitle' | translate" [subtitle]="'finance.profitSubtitle' | translate">
      <a class="p-button p-button-outlined link-button" [href]="service.profitabilityXlsxUrl(filters())" download>
        <i class="pi pi-file-excel" aria-hidden="true"></i> {{ 'finance.export' | translate }}</a>
    </s2s-page-header>
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="pf-from">{{ 'finance.from' | translate }}</label>
          <input pInputText id="pf-from" type="date" [ngModel]="filters().from" (ngModelChange)="set({ from: $event || null })" /></div>
        <div class="field"><label class="s2s-muted" for="pf-to">{{ 'finance.to' | translate }}</label>
          <input pInputText id="pf-to" type="date" [ngModel]="filters().to" (ngModelChange)="set({ to: $event || null })" /></div>
        <div class="field"><label class="s2s-muted" for="pf-status">{{ 'finance.jobStatus' | translate }}</label>
          <p-select inputId="pf-status" [options]="statusOptions()" optionLabel="label" optionValue="value" [ngModel]="filters().status ?? null"
            (ngModelChange)="set({ status: $event })" appendTo="body" /></div>
      </div>
      <ul class="items">
        @for (r of rows(); track r.jobId) {
          <li>
            <div class="line1"><a [routerLink]="['/jobs', r.jobId, 'finance']"><strong>{{ r.jobNumber }}</strong></a> <s2s-status-tag [status]="r.status" />
              <span class="s2s-muted">{{ r.clientName }} · {{ r.jobDate }}</span></div>
            <dl class="figures">
              <div><dt>{{ 'finance.incomeNet' | translate }}</dt><dd>{{ r.currency }} {{ money(r.incomeNet, r.minorUnits) }}</dd></div>
              <div><dt>{{ 'finance.costNet' | translate }}</dt><dd>{{ r.currency }} {{ money(r.costNet, r.minorUnits) }}</dd></div>
              @if (nonZero(r.adjustmentsNet)) { <div><dt>{{ 'finance.adjustmentsNet' | translate }}</dt><dd>{{ money(r.adjustmentsNet, r.minorUnits) }}</dd></div> }
              <div><dt>{{ 'finance.margin' | translate }}</dt><dd [class.negative]="negative(r.margin)">{{ r.currency }} {{ money(r.margin, r.minorUnits) }}
                @if (r.marginPercent !== null) { ({{ 'finance.marginPercent' | translate: { value: r.marginPercent } }}) }</dd></div>
            </dl>
            @if (r.fdaIssued) { <span class="s2s-muted">{{ 'finance.fdaIssued' | translate }}</span> }
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="finance.noProfit" icon="pi pi-chart-line" /></li>
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
    .link-button { display: inline-flex; align-items: center; gap: 0.4rem; text-decoration: none; }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin-bottom: 0.75rem; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 10rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items li { padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); overflow-wrap: anywhere; }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .figures { display: grid; grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr)); gap: 0.25rem 1rem; margin: 0.4rem 0 0; }
    .figures > * { min-width: 0; }
    .figures dt { font-size: 0.75rem; color: var(--s2s-muted, #6b7280); }
    .figures dd { margin: 0; font-variant-numeric: tabular-nums; }
    .negative { color: var(--p-red-600, #dc2626); }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfitabilityComponent {
  protected readonly service = inject(FinanceListsService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly load$ = new Subject<void>();

  protected readonly filters = signal<ProfitabilityFilters>({ status: null, from: null, to: null });
  protected readonly rows = signal<ProfitabilityRow[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly pages = computed(() => Math.ceil(this.total() / SIZE));
  protected readonly statusOptions = computed(() => {
    this.language.current();
    return [{ value: null as string | null, label: this.translate.instant('finance.allJobStatuses') },
      ...['OPEN', 'CLOSED', 'CANCELLED'].map((s) => ({ value: s as string | null, label: this.translate.instant(`status.${s}`) }))];
  });

  constructor() {
    this.load$.pipe(
      startWith(undefined),
      switchMap(() => this.service.profitability(this.filters(), this.page(), SIZE).pipe(
        catchError(() => { this.error.set(true); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      this.error.set(false);
      this.rows.set(p.items);
      this.total.set(p.totalElements);
    });
  }

  protected set(change: Partial<ProfitabilityFilters>): void { this.filters.update((f) => ({ ...f, ...change })); this.page.set(0); this.load$.next(); }
  protected go(page: number): void { this.page.set(page); this.load$.next(); }
  protected money(value: string | number, minorUnits: number): string { return formatMoney(value, minorUnits); }
  protected negative(value: string | number): boolean { return toBig(value).lt(0); }
  protected nonZero(value: string | number): boolean { return !toBig(value).eq(0); }
}
