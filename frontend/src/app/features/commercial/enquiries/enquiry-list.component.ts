import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import { PageQuery } from '../../../core/api/api.models';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { CanDirective } from '../../../shared/directives/can.directive';
import { catchError, of } from 'rxjs';
import { ENQUIRY_STATUSES, EnquiryDraft, EnquiryDraftService, EnquiryService, EnquiryStatus, EnquirySummary } from './enquiry.service';

/** Enquiry register (FR-J01): newest first, searchable by reference, client or vessel. */
@Component({
  selector: 's2s-enquiry-list',
  imports: [DatePipe, FormsModule, RouterLink, TranslatePipe, Button, InputText, Select, DataTableComponent, PageHeaderComponent, CanDirective],
  templateUrl: './enquiry-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnquiryListComponent {
  private readonly service = inject(EnquiryService);
  private readonly draftService = inject(EnquiryDraftService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly lookups = inject(LookupService);

  protected readonly permissions = Permissions;
  protected readonly status = signal<EnquiryStatus | null>(null);
  protected readonly branchId = signal<string | null>(null);
  protected readonly branches = toSignal(this.lookups.branches(), { initialValue: [] });
  protected readonly drafts = signal<EnquiryDraft[]>([]);

  constructor() {
    this.draftService.list().pipe(catchError(() => of([] as EnquiryDraft[])), takeUntilDestroyed()).subscribe(drafts => this.drafts.set(drafts));
  }
  protected readonly receivedFrom = signal<string | null>(null);
  protected readonly receivedTo = signal<string | null>(null);
  private readonly lastQuery = signal<PageQuery | null>(null);
  protected readonly params = computed(() => ({ status: this.status(), branchId: this.branchId(), receivedFrom: this.receivedFrom(), receivedTo: this.receivedTo(), timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone }));
  protected readonly load = (query: PageQuery) => {
    this.lastQuery.set(query);
    return this.service.list(query);
  };
  protected readonly xlsxUrl = computed(() => this.service.xlsxUrl({
    ...this.params(),
    q: this.lastQuery()?.q ?? null,
    sort: this.lastQuery()?.sort ?? 'receivedAt,desc',
  }));

  protected readonly statusOptions = computed(() => {
    this.language.current();
    return ENQUIRY_STATUSES.map((value) => ({ value, label: this.translate.instant(`status.${value}`) }));
  });

  protected readonly columns: ColumnDef<EnquirySummary>[] = [
    { key: 'srNo', headerKey: 'enquiry.srNo', value: (_row, index) => index, width: '4rem' },
    { key: 'reference', headerKey: 'enquiry.reference', sortField: 'reference', link: (row) => ['/commercial/enquiries', row.id], width: '13rem' },
    { key: 'receivedAt', headerKey: 'enquiry.receivedAt', sortField: 'receivedAt', type: 'datetime', width: '12rem' },
    { key: 'clientName', headerKey: 'enquiry.client' },
    { key: 'vessels', headerKey: 'enquiry.vessels', value: (row) => [row.motherVesselName, row.daughterVesselName].filter(Boolean).join(' / ') },
    { key: 'cargoGrades', headerKey: 'enquiry.cargo', value: (row) => row.cargoGrades.join(', ') },
    { key: 'branchCode', headerKey: 'enquiry.branch' },
    { key: 'jobNumber', headerKey: 'enquiry.jobNumber' },
    { key: 'clientStatus', headerKey: 'enquiry.clientStatus' },
    { key: 'estimatedCost', headerKey: 'enquiry.estimatedCost', value: row => row.estimatedCost == null ? '—' : `${row.estimatedCurrency} ${row.estimatedCost.toFixed(2)}` },
    { key: 'fdaStatus', headerKey: 'enquiry.fdaStatus' },
    { key: 'expenses', headerKey: 'enquiry.expenses', value: row => row.expenses == null ? '—' : `${row.jobCurrency} ${row.expenses.toFixed(2)}` },
    { key: 'grossProfit', headerKey: 'enquiry.grossProfit', value: row => {
      if (row.grossProfit == null) return '—';
      const amount = `${row.jobCurrency ?? ''} ${row.grossProfit.toFixed(2)}`.trim();
      return row.grossProfitPercent == null ? amount : `${amount} (${row.grossProfitPercent.toFixed(1)}%)`;
    } },
    { key: 'balanceReceivable', headerKey: 'enquiry.balanceReceivable', value: row => row.balanceReceivable == null ? '—' : `${row.jobCurrency} ${row.balanceReceivable.toFixed(2)}` },
    { key: 'status', headerKey: 'masters.status', sortField: 'status', type: 'status', width: '9rem' },
  ];

  protected readonly actions: RowAction<EnquirySummary>[] = [
    { icon: 'pi pi-angle-right', labelKey: 'common.open', run: (row) => void this.router.navigate(['/commercial/enquiries', row.id]) },
  ];
}
