import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, of, startWith, switchMap } from 'rxjs';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { CargoTotal, GROUPS, GroupBy, HistoryFilters, HistoryOptions, JobHistory, NamedOption, ReportsService } from './reports.service';

const SIZE = 25;
const JOB_STATUSES = ['OPEN', 'DOCUMENTS_PENDING', 'PLANNING', 'APPROVED_FOR_READINESS', 'MOBILISING', 'IN_PROGRESS', 'DEMOBILISING',
  'REPORT_PENDING', 'FINANCIAL_CLOSING', 'CLOSED', 'ON_HOLD', 'CANCELLED'];
const EMPTY_OPTIONS: HistoryOptions = { clients: [], vessels: [], cargoGrades: [] };

/** Job history (management report): jobs by client, vessel, cargo grade or month, with planned cargo totals. */
@Component({
  selector: 's2s-job-history',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, InputText, Message, Select, EmptyStateComponent, PageHeaderComponent, StatusTagComponent],
  template: `
    <s2s-page-header [heading]="'reports.historyTitle' | translate" [subtitle]="'reports.historySubtitle' | translate">
      <a class="p-button p-button-outlined link-button" [href]="service.jobHistoryXlsxUrl(filters(), groupBy())" download>
        <i class="pi pi-file-excel" aria-hidden="true"></i> {{ 'reports.export' | translate }}</a>
    </s2s-page-header>
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="jh-group">{{ 'reports.groupBy' | translate }}</label>
          <p-select inputId="jh-group" [options]="groupOptions()" optionLabel="label" optionValue="value" [ngModel]="groupBy()"
            (ngModelChange)="regroup($event)" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="jh-from">{{ 'reports.from' | translate }}</label>
          <input pInputText id="jh-from" type="date" [ngModel]="filters().from" (ngModelChange)="set({ from: $event || null })" /></div>
        <div class="field"><label class="s2s-muted" for="jh-to">{{ 'reports.to' | translate }}</label>
          <input pInputText id="jh-to" type="date" [ngModel]="filters().to" (ngModelChange)="set({ to: $event || null })" /></div>
        <div class="field"><label class="s2s-muted" for="jh-client">{{ 'reports.client' | translate }}</label>
          <p-select inputId="jh-client" [options]="pick(options().clients)" optionLabel="name" optionValue="id" [ngModel]="filters().clientId"
            (ngModelChange)="set({ clientId: $event })" [filter]="true" filterBy="name" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="jh-vessel">{{ 'reports.vessel' | translate }}</label>
          <p-select inputId="jh-vessel" [options]="pick(options().vessels)" optionLabel="name" optionValue="id" [ngModel]="filters().vesselId"
            (ngModelChange)="set({ vesselId: $event })" [filter]="true" filterBy="name" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="jh-grade">{{ 'reports.cargoGrade' | translate }}</label>
          <p-select inputId="jh-grade" [options]="pick(options().cargoGrades)" optionLabel="name" optionValue="id" [ngModel]="filters().cargoGradeId"
            (ngModelChange)="set({ cargoGradeId: $event })" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="jh-status">{{ 'reports.jobStatus' | translate }}</label>
          <p-select inputId="jh-status" [options]="statusOptions()" optionLabel="name" optionValue="id" [ngModel]="filters().status"
            (ngModelChange)="set({ status: $event })" appendTo="body" /></div>
      </div>
      @if (report()?.capped) { <p-message severity="warn" [text]="'reports.capped' | translate" /> }
    </section>

    <section class="s2s-panel" aria-labelledby="jh-summary">
      <h2 id="jh-summary">{{ 'reports.summaryBy' | translate: { group: groupLabel() } }}</h2>
      <ul class="items">
        @for (g of report()?.groups ?? []; track g.key) {
          <li class="group"><strong>{{ g.label }}</strong>
            <span class="count">{{ 'reports.jobsCount' | translate: { count: g.jobs } }}</span>
            <span class="s2s-muted">{{ cargo(g.cargo) }}</span></li>
        } @empty { <li><s2s-empty-state messageKey="reports.noJobs" icon="pi pi-chart-bar" /></li> }
      </ul>
    </section>

    <section class="s2s-panel" aria-labelledby="jh-jobs">
      <h2 id="jh-jobs">{{ 'reports.jobs' | translate }} ({{ report()?.jobs?.totalElements ?? 0 }})</h2>
      <ul class="items">
        @for (j of report()?.jobs?.items ?? []; track j.jobId) {
          <li>
            <div class="line1"><a [routerLink]="['/jobs', j.jobId]"><strong>{{ j.jobNumber }}</strong></a> <s2s-status-tag [status]="j.status" />
              <span class="s2s-muted">{{ j.jobDate }} · {{ j.client ?? '—' }}</span></div>
            <div class="line2 s2s-muted">{{ j.motherVessel ?? '—' }} / {{ j.daughterVessel ?? '—' }} · {{ cargo(j.cargo) }}</div>
          </li>
        }
      </ul>
      @if (pages() > 1) {
        <div class="pager">
          <p-button [label]="'reports.previous' | translate" icon="pi pi-chevron-left" [text]="true" [disabled]="page() === 0" (onClick)="go(page() - 1)" />
          <span class="s2s-muted">{{ 'reports.pageOf' | translate: { page: page() + 1, pages: pages() } }}</span>
          <p-button [label]="'reports.next' | translate" icon="pi pi-chevron-right" iconPos="right" [text]="true" [disabled]="page() + 1 >= pages()" (onClick)="go(page() + 1)" />
        </div>
      }
    </section>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    h2 { font-size: 1rem; margin: 0 0 0.5rem; }
    .link-button { display: inline-flex; align-items: center; gap: 0.4rem; text-decoration: none; }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 11rem; min-width: 0; }
    .field input, .field :is(.p-select) { width: 100%; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items li { padding: 0.65rem 0; border-top: 1px solid var(--s2s-border); overflow-wrap: anywhere; }
    .group { display: flex; flex-wrap: wrap; gap: 0.35rem 1rem; align-items: baseline; }
    .count { font-variant-numeric: tabular-nums; font-weight: 600; color: var(--p-primary-600, #84252e); }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .line2 { margin-top: 0.3rem; font-size: 0.85rem; }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobHistoryComponent {
  protected readonly service = inject(ReportsService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly load$ = new Subject<void>();

  protected readonly filters = signal<HistoryFilters>({ from: null, to: null, clientId: null, vesselId: null, cargoGradeId: null,
    status: null });
  protected readonly groupBy = signal<GroupBy>('CLIENT');
  protected readonly report = signal<JobHistory | null>(null);
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly options = toSignal(this.service.options().pipe(catchError(() => of(EMPTY_OPTIONS))), { initialValue: EMPTY_OPTIONS });
  protected readonly pages = computed(() => Math.ceil((this.report()?.jobs.totalElements ?? 0) / SIZE));
  protected readonly groupOptions = computed(() => {
    this.language.current();
    return GROUPS.map((value) => ({ value, label: this.translate.instant(`reports.groups.${value}`) }));
  });
  protected readonly groupLabel = computed(() => this.groupOptions().find((o) => o.value === this.groupBy())?.label ?? '');
  protected readonly statusOptions = computed(() => {
    this.language.current();
    return this.pick(JOB_STATUSES.map((s) => ({ id: s, name: this.translate.instant(`status.${s}`) })));
  });

  constructor() {
    this.load$.pipe(startWith(undefined), switchMap(() => this.service.jobHistory(this.filters(), this.groupBy(), this.page(), SIZE)
      .pipe(catchError(() => { this.error.set(true); return EMPTY; }))), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((r) => { this.error.set(false); this.report.set(r); });
  }

  /** "All" first, so every filter can be cleared from its own list. */
  protected pick(list: NamedOption[]): { id: string | null; name: string }[] {
    return [{ id: null, name: this.translate.instant('reports.all') }, ...list];
  }

  protected set(change: Partial<HistoryFilters>): void { this.filters.update((f) => ({ ...f, ...change })); this.page.set(0); this.load$.next(); }
  protected regroup(groupBy: GroupBy): void { this.groupBy.set(groupBy); this.page.set(0); this.load$.next(); }
  protected go(page: number): void { this.page.set(page); this.load$.next(); }
  protected cargo(list: CargoTotal[]): string {
    return list.map((c) => `${c.grade} ${c.quantity.toLocaleString()} ${c.unit}`).join(' · ') || '—';
  }
}
