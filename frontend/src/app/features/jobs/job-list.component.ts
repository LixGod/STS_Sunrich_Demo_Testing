import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import { PageQuery } from '../../core/api/api.models';
import { LanguageService } from '../../core/i18n/language.service';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { JobService, JobSummary } from './job.service';

const JOB_STATUSES = ['OPEN', 'DOCUMENTS_PENDING', 'PLANNING', 'APPROVED_FOR_READINESS', 'MOBILISING', 'IN_PROGRESS',
  'DEMOBILISING', 'REPORT_PENDING', 'FINANCIAL_CLOSING', 'CLOSED', 'ON_HOLD', 'SUSPENDED', 'CANCELLED'];

type JobFilters = Record<'jobNumber' | 'createdFrom' | 'createdTo' | 'client' | 'vessel' | 'enquiryReference' | 'status', string | null>;

/** Register of STS jobs, newest first. */
@Component({
  selector: 's2s-job-list',
  imports: [FormsModule, TranslatePipe, InputText, Select, DataTableComponent, PageHeaderComponent],
  template: `
    <s2s-page-header [heading]="'job.title' | translate" [subtitle]="(forInvoice ? 'finance.chooseInvoiceJob' : 'job.subtitle') | translate" />
    <section class="s2s-panel">
      <s2s-data-table [columns]="columns" [load]="load" [params]="params()" [actions]="actions" [stackedToolbar]="true"
        defaultSort="createdAt,desc" captionKey="nav.jobs" emptyKey="job.empty">
      <div tableToolbar class="filters">
        <div class="field"><label for="job-number">{{ 'job.number' | translate }}</label>
          <input pInputText id="job-number" type="search" [ngModel]="filters().jobNumber" [ngModelOptions]="{ updateOn: 'blur' }"
            (ngModelChange)="set('jobNumber', $event)" (keyup.enter)="$any($event.target).blur()" /></div>
        <div class="field"><label for="job-created-from">{{ 'job.created' | translate }} {{ 'reports.from' | translate }}</label>
          <input pInputText id="job-created-from" type="date" [ngModel]="filters().createdFrom" (ngModelChange)="set('createdFrom', $event)" /></div>
        <div class="field"><label for="job-created-to">{{ 'job.created' | translate }} {{ 'reports.to' | translate }}</label>
          <input pInputText id="job-created-to" type="date" [ngModel]="filters().createdTo" (ngModelChange)="set('createdTo', $event)" /></div>
        <div class="field"><label for="job-client">{{ 'job.client' | translate }}</label>
          <input pInputText id="job-client" type="search" [ngModel]="filters().client" [ngModelOptions]="{ updateOn: 'blur' }"
            (ngModelChange)="set('client', $event)" (keyup.enter)="$any($event.target).blur()" /></div>
        <div class="field"><label for="job-vessel">{{ 'job.vessels' | translate }}</label>
          <input pInputText id="job-vessel" type="search" [ngModel]="filters().vessel" [ngModelOptions]="{ updateOn: 'blur' }"
            (ngModelChange)="set('vessel', $event)" (keyup.enter)="$any($event.target).blur()" /></div>
        <div class="field"><label for="job-enquiry">{{ 'job.enquiry' | translate }}</label>
          <input pInputText id="job-enquiry" type="search" [ngModel]="filters().enquiryReference" [ngModelOptions]="{ updateOn: 'blur' }"
            (ngModelChange)="set('enquiryReference', $event)" (keyup.enter)="$any($event.target).blur()" /></div>
        <div class="field"><label for="job-status">{{ 'job.status' | translate }}</label>
          <p-select inputId="job-status" [options]="statusOptions()" optionLabel="label" optionValue="value"
            [showClear]="true" [placeholder]="'filters.allStatuses' | translate"
            [ngModel]="filters().status" (ngModelChange)="set('status', $event)" appendTo="body" /></div>
      </div>
      </s2s-data-table>
    </section>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; width: 100%; }
    .field { display: flex; flex: 1 1 10rem; flex-direction: column; gap: 0.25rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobListComponent {
  private readonly service = inject(JobService);
  private readonly router = inject(Router);
  protected readonly forInvoice = inject(ActivatedRoute).snapshot.queryParamMap.get('invoice') === '1';
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);

  protected readonly filters = signal<JobFilters>({ jobNumber: null, createdFrom: null, createdTo: null,
    client: null, vessel: null, enquiryReference: null, status: null });
  protected readonly params = computed(() => ({ ...this.filters(), timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone }));
  protected readonly statusOptions = computed(() => {
    this.language.current();
    return JOB_STATUSES.map((value) => ({ value, label: this.translate.instant(`status.${value}`) }));
  });

  protected set(key: keyof JobFilters, value: string | null): void {
    this.filters.update((current) => ({ ...current, [key]: value || null }));
  }

  protected readonly load = (query: PageQuery) => this.service.list(query);

  protected readonly columns: ColumnDef<JobSummary>[] = [
    { key: 'jobNumber', headerKey: 'job.number', sortField: 'jobNumber', link: (row) => this.forInvoice ? ['/jobs', row.id, 'invoices'] : ['/jobs', row.id], width: '14rem' },
    { key: 'createdAt', headerKey: 'job.created', sortField: 'createdAt', type: 'datetime', width: '12rem' },
    { key: 'clientName', headerKey: 'job.client' },
    { key: 'vessels', headerKey: 'job.vessels', value: (row) => [row.motherVesselName, row.daughterVesselName].filter(Boolean).join(' / ') },
    { key: 'enquiryReference', headerKey: 'job.enquiry' },
    { key: 'status', headerKey: 'job.status', sortField: 'status', type: 'status', width: '8rem' },
  ];

  protected readonly actions: RowAction<JobSummary>[] = [
    { icon: this.forInvoice ? 'pi pi-file-plus' : 'pi pi-angle-right', labelKey: this.forInvoice ? 'finance.newInvoice' : 'common.open',
      run: (row) => void this.router.navigate(this.forInvoice ? ['/jobs', row.id, 'invoices'] : ['/jobs', row.id]) },
  ];
}
