import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Subject, EMPTY, catchError, startWith, switchMap } from 'rxjs';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { BUCKETS, FinanceService, Money, ReceivablesPage, formatMoney, localToday } from './finance.service';

/** Open issued invoices as at a chosen day, aged from the due date, with totals per client and currency and an Excel export. */
@Component({
  selector: 's2s-receivables',
  imports: [FormsModule, RouterLink, TranslatePipe, InputText, Message, EmptyStateComponent, PageHeaderComponent],
  template: `
    <s2s-page-header [heading]="'finance.receivablesTitle' | translate" [subtitle]="'finance.receivablesSubtitle' | translate">
      <a class="p-button p-button-outlined link-button" [href]="service.receivablesXlsxUrl(asOf())" download>
        <i class="pi pi-file-excel" aria-hidden="true"></i> {{ 'finance.export' | translate }}</a>
    </s2s-page-header>
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <label class="field" for="rv-asof"><span class="s2s-muted">{{ 'finance.asOf' | translate }}</span></label>
      <input pInputText id="rv-asof" type="date" [ngModel]="asOf()" (ngModelChange)="change($event)" />
      <p class="s2s-muted small">{{ 'finance.bucketsHint' | translate }}</p>
    </section>
    @if (data(); as d) {
      <section class="s2s-panel">
        <h2>{{ 'finance.byClient' | translate }}</h2>
        @for (t of d.totals; track t.clientName + t.currency) {
          <div class="total-card">
            <div class="line1"><strong>{{ t.clientName }}</strong> <span>{{ t.currency }} {{ money(t.total, t.minorUnits) }}</span></div>
            <dl class="buckets">
              @for (b of buckets; track b) {
                <div><dt>{{ 'finance.bucket.' + b | translate }}</dt><dd>{{ money(t.buckets[b], t.minorUnits) }}</dd></div>
              }
            </dl>
          </div>
        } @empty {
          <s2s-empty-state messageKey="finance.noReceivables" icon="pi pi-check-circle" />
        }
      </section>
      @if (d.rows.totalElements) {
        <section class="s2s-panel">
          <h2>{{ 'finance.byInvoice' | translate }}</h2>
          <ul class="items">
            @for (r of d.rows.items; track r.invoiceId) {
              <li><a [routerLink]="['/finance/invoices', r.invoiceId]">
                <span class="line1"><strong>{{ r.number }}</strong> <span>{{ r.currency }} {{ money(r.outstandingAmount, r.minorUnits) }}</span></span>
                <span class="s2s-muted">{{ r.clientName }} · {{ r.jobNumber }} · {{ 'finance.due' | translate: { date: r.dueDate ?? '—' } }}</span>
                <span [class.overdue]="r.overdueDays > 0">{{ r.overdueDays > 0 ? ('finance.daysOverdue' | translate: { days: r.overdueDays }) : ('finance.notDue' | translate) }}
                  · {{ 'finance.bucket.' + r.bucket | translate }}</span>
              </a></li>
            }
          </ul>
          <nav class="pager" aria-label="Receivables pages">
            <button type="button" [disabled]="page() === 0" (click)="goTo(page() - 1)">{{ 'finance.previous' | translate }}</button>
            <span>{{ page() + 1 }} / {{ Math.ceil(d.rows.totalElements / d.rows.size) }}</span>
            <button type="button" [disabled]="(page() + 1) * d.rows.size >= d.rows.totalElements" (click)="goTo(page() + 1)">{{ 'finance.next' | translate }}</button>
          </nav>
        </section>
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    h2 { font-size: 1rem; margin: 0 0 0.5rem; }
    .field { display: block; margin-bottom: 0.25rem; }
    #rv-asof { width: 100%; max-width: 14rem; }
    .small { font-size: 0.85rem; margin: 0.5rem 0 0; }
    .link-button { display: inline-flex; align-items: center; gap: 0.4rem; text-decoration: none; }
    .total-card { padding: 0.5rem 0; border-top: 1px solid var(--s2s-border); }
    .line1 { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 0.5rem; overflow-wrap: anywhere; }
    .line1 span { font-variant-numeric: tabular-nums; }
    .buckets { display: grid; grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr)); gap: 0.25rem 1rem; margin: 0.25rem 0 0; }
    .buckets > * { min-width: 0; }
    .buckets dt { font-size: 0.75rem; color: var(--s2s-muted, #6b7280); }
    .buckets dd { margin: 0; font-variant-numeric: tabular-nums; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items a { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); color: inherit;
      text-decoration: none; min-height: 44px; overflow-wrap: anywhere; }
    .overdue { color: var(--p-red-600, #dc2626); }
    .pager { display: flex; align-items: center; justify-content: center; gap: 1rem; margin-top: 0.75rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReceivablesComponent {
  protected readonly service = inject(FinanceService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly load$ = new Subject<void>();

  protected readonly buckets = BUCKETS;
  protected readonly Math = Math;
  protected readonly asOf = signal(localToday());
  protected readonly page = signal(0);
  protected readonly data = signal<ReceivablesPage | null>(null);
  protected readonly error = signal(false);

  constructor() {
    this.load$.pipe(
      startWith(undefined),
      switchMap(() => this.service.receivablesPage(this.asOf() || null, this.page()).pipe(
        catchError(() => { this.error.set(true); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((d) => {
      this.error.set(false);
      this.data.set(d);
    });
  }

  protected change(value: string): void {
    this.asOf.set(value);
    this.page.set(0);
    this.load$.next();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load$.next();
  }

  protected money(value: Money, minorUnits: number): string {
    return formatMoney(value, minorUnits);
  }
}
