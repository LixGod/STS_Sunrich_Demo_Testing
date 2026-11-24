import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Message } from 'primeng/message';
import { problemKey, toProblem } from '../../core/api/api.models';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { FinanceService, JobInvoices, formatMoney } from './finance.service';

/** A job's invoices and credit notes, and where a new invoice is started (from the accepted quotation or blank). Shown in the job's Finance tabs. */
@Component({
  selector: 's2s-job-invoices',
  imports: [RouterLink, TranslatePipe, Message, EmptyStateComponent, StatusTagComponent],
  template: `
    @if (errorKey(); as key) { <p-message severity="error" role="alert" [text]="key | translate" /> }
    @if (overview(); as o) {
      @if (o.canCreate) {
        <section class="invoice-toolbar">
          <div class="toolbar-actions">
            @if (o.hasAcceptedQuotation) {
              <button type="button" class="btn-create-primary" [disabled]="saving()" (click)="create(true)">
                <i class="pi pi-file-plus" aria-hidden="true"></i> {{ 'finance.fromQuotation' | translate }}
              </button>
            } @else {
              <span class="no-quote-hint"><i class="pi pi-info-circle" aria-hidden="true"></i> {{ 'finance.noAcceptedQuotation' | translate }}</span>
            }
            <button type="button" class="btn-create-secondary" [disabled]="saving()" (click)="create(false)">
              <i class="pi pi-file" aria-hidden="true"></i> {{ 'finance.blankInvoice' | translate }}
            </button>
          </div>
        </section>
      }
      <div class="invoices-list">
        @for (i of o.invoices; track i.id) {
          <article class="invoice-card">
            <a [routerLink]="['/finance/invoices', i.id]" class="invoice-card-link">
              <div class="invoice-col-main">
                <div class="invoice-title-row">
                  <span class="doc-badge" [class.credit-note]="i.docType === 'CREDIT_NOTE'">
                    {{ 'finance.docType.' + i.docType | translate }}
                  </span>
                  <strong class="invoice-number">{{ i.number ?? ('finance.draftNumber' | translate) }}</strong>
                  <s2s-status-tag [status]="i.status" />
                </div>
                <div class="invoice-date-row">
                  <i class="pi pi-calendar" aria-hidden="true"></i>
                  <span>{{ i.invoiceDate }}</span>
                </div>
              </div>
              <div class="invoice-col-amounts">
                <div class="gross-amount">
                  <span class="amount-val">{{ i.currency }} {{ money(i.grossAmount, i.minorUnits) }}</span>
                </div>
                @if (i.status === 'ISSUED' && i.docType === 'INVOICE') {
                  <div class="outstanding-pill">
                    <span class="out-val">{{ 'finance.outstanding' | translate: { amount: i.currency + ' ' + money(i.outstandingAmount, i.minorUnits) } }}</span>
                  </div>
                }
              </div>
              <div class="invoice-col-arrow" aria-hidden="true">
                <i class="pi pi-chevron-right"></i>
              </div>
            </a>
          </article>
        } @empty {
          <s2s-empty-state messageKey="finance.jobEmpty" icon="pi pi-wallet" />
        }
      </div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .invoice-toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.75rem;
      padding: 0.85rem 1.15rem;
      background: var(--s2s-surface);
      border: 1px solid var(--s2s-border);
      border-radius: var(--s2s-radius);
      box-shadow: var(--s2s-shadow);
    }
    .toolbar-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.65rem;
    }
    .btn-create-primary {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.5rem 1rem;
      min-height: 40px;
      font-size: 0.88rem;
      font-weight: 600;
      color: #ffffff;
      background: var(--s2s-brand);
      border: 1px solid transparent;
      border-radius: var(--s2s-radius);
      cursor: pointer;
      box-shadow: var(--s2s-shadow);
      transition: background 0.15s ease;
      &:hover:not([disabled]) { background: var(--s2s-brand-dark); }
    }
    .btn-create-secondary {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.5rem 1rem;
      min-height: 40px;
      font-size: 0.88rem;
      font-weight: 600;
      color: var(--s2s-text);
      background: var(--s2s-silver);
      border: 1px solid var(--s2s-border);
      border-radius: var(--s2s-radius);
      cursor: pointer;
      transition: background 0.15s ease;
      &:hover:not([disabled]) { background: #e2e8f0; }
    }
    .no-quote-hint {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      font-size: 0.85rem;
      color: var(--s2s-text-muted);
    }
    .invoices-list {
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
    }
    .invoice-card {
      min-width: 0;
      border: 1px solid var(--s2s-border);
      border-radius: var(--s2s-radius);
      background: var(--s2s-surface);
      box-shadow: var(--s2s-shadow);
      transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.15s ease;
      content-visibility: auto;
      contain-intrinsic-size: auto 76px;
      &:hover {
        box-shadow: var(--s2s-shadow-md);
        border-color: #cbd5e1;
        transform: translateY(-1px);
      }
    }
    .invoice-card-link {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem 1.25rem;
      color: inherit;
      text-decoration: none;
      min-height: 44px;
      overflow-wrap: anywhere;
    }
    .invoice-col-main {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      min-width: 0;
    }
    .invoice-title-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.6rem;
    }
    .doc-badge {
      display: inline-flex;
      align-items: center;
      padding: 0.15rem 0.5rem;
      border-radius: 6px;
      font-size: 0.72rem;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      background: #e0e7ff;
      color: #3730a3;
      &.credit-note {
        background: #fef3c7;
        color: #92400e;
      }
    }
    .invoice-number {
      font-size: 1.02rem;
      color: var(--s2s-text);
    }
    .invoice-date-row {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      font-size: 0.82rem;
      color: var(--s2s-text-muted);
    }
    .invoice-col-amounts {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 0.3rem;
      white-space: nowrap;
    }
    .gross-amount {
      font-size: 1.15rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      color: var(--s2s-text);
    }
    .outstanding-pill {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.15rem 0.5rem;
      border-radius: 6px;
      background: #fef2f2;
      border: 1px solid #fecaca;
      font-size: 0.78rem;
      .out-label { color: #991b1b; }
      .out-val { font-weight: 700; color: #b91c1c; font-variant-numeric: tabular-nums; }
    }
    .invoice-col-arrow {
      color: var(--s2s-text-muted);
      font-size: 0.95rem;
      padding-left: 0.5rem;
    }
    @media (max-width: 640px) {
      .invoice-card-link {
        flex-direction: column;
        align-items: flex-start;
        gap: 0.75rem;
      }
      .invoice-col-amounts {
        align-items: flex-start;
      }
      .invoice-col-arrow {
        display: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobInvoicesComponent {
  private readonly service = inject(FinanceService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /** Job id. */
  readonly jobId = input.required<string>();

  protected readonly overview = signal<JobInvoices | null>(null);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  constructor() {
    effect(() => {
      const id = this.jobId();
      untracked(() => this.service.jobInvoices(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (o) => this.overview.set(o),
        error: () => this.errorKey.set('errors.loadFailed'),
      }));
    });
  }

  protected create(fromQuotation: boolean): void {
    this.saving.set(true);
    this.service.create(this.jobId(), fromQuotation).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (invoice) => void this.router.navigate(['/finance/invoices', invoice.id]),
      error: (e: unknown) => {
        this.saving.set(false);
        this.errorKey.set(problemKey(toProblem(e)));
      },
    });
  }

  protected money(value: string | number, minorUnits: number): string {
    return formatMoney(value, minorUnits);
  }
}
