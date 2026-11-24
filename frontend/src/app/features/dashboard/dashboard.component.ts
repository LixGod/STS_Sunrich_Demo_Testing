import { HttpClient, HttpContext } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, Signal, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ChartConfiguration } from 'chart.js';
import { Observable, catchError, map, of } from 'rxjs';
import { API_BASE_URL, Page, QueryValue, SILENT_ERRORS, toHttpParams } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { ActionInboxComponent } from '../../shared/components/action-inbox/action-inbox.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import Big from 'big.js';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { FLOW_STEPS, STATUS_STEP } from '../../core/sts-flow';
import { DashboardChartComponent, sharePercent } from './dashboard-chart.component';

/** null = loading, 'error' = could not be loaded (never shown as zero). */
type Loaded<T> = T | 'error' | null;

interface BoardJob { id: string; jobNumber: string; status: string; motherVesselName: string | null; daughterVesselName: string | null }

interface LiveJob {
  jobId: string; jobNumber: string; status: string; motherVessel: string | null; daughterVessel: string | null;
  lastEventLabel: string | null; lastEventAt: string | null; openIncidents: number;
}
interface ReceivableTotal { currency: string; buckets: Record<string, number | string>; total: number | string; minorUnits: number }
interface ProfitabilityRow {
  jobId: string; jobNumber: string; clientName: string | null; status: string; currency: string; minorUnits: number;
  incomeNet: number | string; costNet: number | string; margin: number | string; marginPercent: number | string | null;
}
interface Kpi { key: string; labelKey: string; route: string; value: Loaded<number>; attention: boolean }
interface WorkLink { label: string; detail: string; route: string }
interface DashboardChart { config: ChartConfiguration<'bar'>; summary: string }

/** Active statuses grouped by the STS step they sit on, then paused jobs. */
const STAGES = [
  ...[...new Set(Object.values(STATUS_STEP))].filter((step) => step <= FLOW_STEPS.length).sort((a, b) => a - b)
    .map((step) => ({ label: `${step} · ${FLOW_STEPS[step - 1]}`, statuses: Object.keys(STATUS_STEP).filter((s) => STATUS_STEP[s] === step) })),
  { label: 'On hold', statuses: ['ON_HOLD', 'SUSPENDED'] },
];

/** Ageing is ordered, so overdue buckets darken with age on one hue; "not due" stays neutral. */
const AGEING = [
  { key: 'NOT_DUE', label: 'Not due', color: '#a8a6a0' },
  { key: 'D1_30', label: '1–30 days', color: '#e88080' },
  { key: 'D31_60', label: '31–60 days', color: '#d65050' },
  { key: 'D61_90', label: '61–90 days', color: '#b3313a' },
  { key: 'D90_PLUS', label: 'Over 90 days', color: '#7a1f28' },
];

/**
 * Role-aware landing page: work waiting for the user first (action inbox and approvals), then live operations, then finance.
 * Every section appears only with its permission, and every figure comes from a scoped API.
 */
@Component({
  selector: 's2s-dashboard',
  imports: [DatePipe, RouterLink, TranslatePipe, ActionInboxComponent, MoneyPipe, StatusTagComponent, UtcDateTimePipe, DashboardChartComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  protected readonly user = this.auth.currentUser;
  protected readonly today = new Date();

  protected readonly myWork = computed<{ title: string; steps: WorkLink[] }>(() => {
    const isStsAdmin = this.user()?.grants.some(grant => grant.roleCode === 'STS_ADMIN');
    if (this.can(Permissions.adminUserWrite)) return { title: 'Admin', steps: [
      { label: 'Manage access', detail: 'Choose who can read, add, edit and approve', route: '/administration/users' },
      ...(this.can(Permissions.auditRead) ? [{ label: 'Review activity', detail: 'Check sign-ins and changes', route: '/administration/audit' }] : []),
    ] };
    if (isStsAdmin && this.can(Permissions.masterCommercialRead)) return { title: 'Admin', steps: [
      { label: 'Set up vessels', detail: 'Save each vessel and its GA plan once', route: '/administration/vessels' },
      ...(this.can(Permissions.masterFinanceRead) ? [{ label: 'Check commercial setup', detail: 'Clients, cargo and tariffs used in estimates', route: '/administration/tariffs' }] : []),
      ...(this.can(Permissions.masterResourceRead) ? [{ label: 'Check equipment', detail: 'Keep fenders and hoses available for jobs', route: '/administration/equipment' }] : []),
    ] };
    if (this.can(Permissions.techJobWrite) && this.can(Permissions.jobRead)) return { title: 'Marine', steps: [
      { label: 'Open a job', detail: 'Collect owner documents, then prepare JPO and mooring', route: '/jobs' },
      { label: 'Check your approvals', detail: 'Review work submitted by another marine officer', route: '/inbox' },
    ] };
    if (this.can(Permissions.enquiryWrite) && this.can(Permissions.enquiryRead)) return { title: 'Commercial', steps: [
      { label: 'Start with an enquiry', detail: 'Select vessels and cargo, then estimate and quote', route: '/commercial/enquiries' },
      ...(this.can(Permissions.jobRead) ? [{ label: 'Follow accepted jobs', detail: 'Check the owner handoff and job status', route: '/jobs' }] : []),
    ] };
    if (this.can(Permissions.opsLiveWrite) && this.can(Permissions.opsLiveRead)) return { title: 'Operations', steps: [
      { label: 'Record the live job', detail: 'Times, cargo progress, delays and incidents', route: '/operations' },
      ...(this.can(Permissions.finCostWrite) && this.can(Permissions.jobRead)
        ? [{ label: 'Prepare job accounts', detail: 'Enter vendor bills, client invoices and the final account', route: '/jobs' }] : []),
      ...(this.auth.hasGlobalPermission(Permissions.masterResourceRead) ? [{ label: 'Check inventory', detail: 'Available, in use and returned equipment', route: '/operations/inventory' }] : []),
    ] };
    if (this.can(Permissions.finCostApprove) && this.can(Permissions.finCostRead)) return { title: 'Finance', steps: [
      ...(this.can(Permissions.finInvoiceApprove) ? [{ label: 'Approve client invoices', detail: 'Review invoices submitted by Operations', route: '/finance/invoices' }] : []),
      { label: 'Approve vendor bills', detail: 'Review bills submitted by Operations', route: '/finance/vendor-bills' },
      ...(this.can(Permissions.finReceiptRead) ? [{ label: 'Record receipts', detail: 'Track what the owner has paid', route: '/finance/receipts' }] : []),
    ] };
    if (this.can(Permissions.finCostPoApprove) && this.can(Permissions.finCostRead)) return { title: 'Management', steps: [
      { label: 'Approve purchase orders', detail: 'Review purchase orders submitted by Operations', route: '/finance/purchase-orders' },
    ] };
    if (this.can(Permissions.jobRead) && this.auth.hasAnyPermission([
      Permissions.techJobReview, Permissions.techJpoApprove, Permissions.techMooringApprove,
      Permissions.opsIncidentReview, Permissions.finInvoiceApprove,
    ])) return { title: 'Review and approvals', steps: [
      { label: 'Check work waiting for review', detail: 'Open your approval and follow-up queue', route: '/inbox' },
      { label: 'Open a job', detail: 'See the source work before deciding', route: '/jobs' },
    ] };
    if (this.can(Permissions.auditRead)) return { title: 'Audit', steps: [
      { label: 'Review activity', detail: 'Check sign-ins and changes', route: '/administration/audit' },
      ...(this.can(Permissions.jobRead) ? [{ label: 'Open jobs', detail: 'Inspect accepted jobs', route: '/jobs' }] : []),
    ] };
    return { title: 'Your work', steps: this.can(Permissions.jobRead)
      ? [{ label: 'Open jobs', detail: 'See accepted jobs', route: '/jobs' }] : [] };
  });

  private can(permission: string) { return this.auth.hasPermission(permission); }
  protected readonly canReadJobs = this.can(Permissions.jobRead);
  protected readonly canReadLive = this.can(Permissions.opsLiveRead);
  protected readonly canApproveInvoices = this.can(Permissions.finInvoiceApprove);
  protected readonly canApproveCosts = this.can(Permissions.finCostApprove);
  protected readonly canApprovePurchaseOrders = this.can(Permissions.finCostPoApprove);
  protected readonly canReadInvoices = this.can(Permissions.finInvoiceRead);
  protected readonly canReadProfitability = this.can(Permissions.finProfitRead);

  // Counts (size 1: only totalElements is used).
  private readonly openActions = this.count('alerts', { state: 'OPEN' }, this.canReadJobs);
  private readonly statusCounts = this.signal(this.load<Record<string, number>>('jobs/status-counts', {}, this.canReadJobs));
  private readonly inOperation = computed<Loaded<number>>(() => {
    const counts = this.statusCounts();
    return counts === null || counts === 'error' ? counts : counts['IN_PROGRESS'] ?? 0;
  });
  private readonly closedJobs = computed<Loaded<number>>(() => {
    const counts = this.statusCounts();
    return counts === null || counts === 'error' ? counts : counts['CLOSED'] ?? 0;
  });
  private readonly overdueInvoices = this.count('finance/invoices', { overdue: true }, this.canReadInvoices);
  protected readonly invoicesAwaiting = this.count('finance/invoices', { status: 'SUBMITTED' }, this.canApproveInvoices);
  protected readonly costsAwaiting = this.count('finance/cost-documents', { status: 'SUBMITTED', kind: 'BILL' }, this.canApproveCosts);
  protected readonly purchaseOrdersAwaiting = this.count('finance/cost-documents', { status: 'SUBMITTED', kind: 'PO' }, this.canApprovePurchaseOrders);

  // Lists.
  protected readonly live = this.signal(this.load<Page<LiveJob>>('operations/jobs', { page: 0, size: 5 }, this.canReadLive));
  protected readonly board = this.signal(this.load<Page<BoardJob>>('jobs', { page: 0, size: 8 }, this.canReadJobs));
  protected readonly stepNumbers = FLOW_STEPS.map((_, i) => i + 1);
  protected readonly receivables = this.signal(this.load<{ totals: ReceivableTotal[] }>('finance/receivables/summary', {}, this.canReadInvoices).pipe(
    map((r) => {
      // Totals arrive per client and currency; the dashboard shows one line per currency.
      const byCurrency = new Map<string, { outstanding: Big; overdue: Big; minorUnits: number; buckets: Record<string, Big> }>();
      for (const t of r?.totals ?? []) {
        const row = byCurrency.get(t.currency)
          ?? { outstanding: new Big(0), overdue: new Big(0), minorUnits: t.minorUnits, buckets: {} as Record<string, Big> };
        row.outstanding = row.outstanding.plus(t.total);
        row.overdue = row.overdue.plus(Object.entries(t.buckets).filter(([b]) => b !== 'NOT_DUE').reduce((s, [, v]) => s.plus(v), new Big(0)));
        for (const [b, v] of Object.entries(t.buckets)) row.buckets[b] = (row.buckets[b] ?? new Big(0)).plus(v);
        byCurrency.set(t.currency, row);
      }
      return [...byCurrency].map(([currency, v]) => ({ currency, ...v, hasOverdue: v.overdue.gt(0) }));
    }),
  ));
  // shortcut: dashboard shows five recent jobs; use the profitability page for the full portfolio.
  protected readonly profitability = this.signal(this.load<Page<ProfitabilityRow>>('finance/profitability', { page: 0, size: 5 }, this.canReadProfitability));

  protected readonly stageChart = computed<(DashboardChart & { total: number }) | null>(() => {
    const counts = this.statusCounts();
    if (counts === null || counts === 'error') return null;
    const rows = STAGES.map((g) => ({ label: g.label, value: g.statuses.reduce((n, s) => n + (counts[s] ?? 0), 0) }));
    return {
      total: rows.reduce((n, r) => n + r.value, 0),
      summary: rows.map((r) => `${r.label}: ${r.value}`).join(', '),
      config: {
        type: 'bar',
        data: { labels: rows.map((r) => r.label), datasets: [{ label: 'Jobs', data: rows.map((r) => r.value), backgroundColor: '#9b2c36', borderRadius: 4, maxBarThickness: 22 }] },
        options: { indexAxis: 'y', plugins: { legend: { display: false } },
          scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { grid: { display: false } } } },
      },
    };
  });

  protected readonly receivablesChart = computed<DashboardChart | null>(() => {
    const rows = this.receivables();
    if (rows === null || rows === 'error' || !rows.length) return null;
    const share = (row: (typeof rows)[number], key: string) => sharePercent(row.buckets[key] ?? new Big(0), row.outstanding);
    return {
      summary: rows.map((row) => `${row.currency}: ${AGEING.map((a) => `${a.label} ${share(row, a.key)}%`).join(', ')}`).join('; '),
      config: {
        type: 'bar',
        data: { labels: rows.map((row) => row.currency), datasets: AGEING.map((a) => ({
          label: a.label, data: rows.map((row) => share(row, a.key)), backgroundColor: a.color, borderColor: '#ffffff', borderWidth: 1, maxBarThickness: 28,
        })) },
        options: { indexAxis: 'y',
          plugins: { legend: { position: 'bottom' }, tooltip: { callbacks: { label: (ctx) => `${ctx.dataset.label}: ${ctx.parsed.x}%` } } },
          scales: { x: { stacked: true, beginAtZero: true, max: 100, ticks: { callback: (value) => `${value}%` } }, y: { stacked: true, grid: { display: false } } } },
      },
    };
  });

  protected readonly marginChart = computed<DashboardChart | null>(() => {
    const page = this.profitability();
    if (page === null || page === 'error') return null;
    const jobs = page.items.filter((job) => job.marginPercent !== null);
    if (!jobs.length) return null;
    const values = jobs.map((job) => Number(job.marginPercent));
    return {
      summary: jobs.map((job, i) => `${job.jobNumber}: ${values[i]}%`).join(', '),
      config: {
        type: 'bar',
        data: { labels: jobs.map((job) => job.jobNumber), datasets: [{
          label: 'Margin %', data: values, backgroundColor: values.map((v) => (v < 0 ? '#d03b3b' : '#2f8f5b')), borderRadius: 4, maxBarThickness: 36,
        }] },
        options: { plugins: { legend: { display: false }, tooltip: { callbacks: { label: (ctx) => `Margin: ${ctx.parsed.y}%` } } },
          scales: { y: { ticks: { callback: (value) => `${value}%` } }, x: { grid: { display: false } } } },
      },
    };
  });

  protected readonly canSeeApprovals = this.canApproveInvoices || this.canApproveCosts || this.canApprovePurchaseOrders;

  private readonly awaitingApproval = computed<Loaded<number>>(() => {
    const parts = [this.canApproveInvoices ? this.invoicesAwaiting() : 0,
      this.canApproveCosts ? this.costsAwaiting() : 0, this.canApprovePurchaseOrders ? this.purchaseOrdersAwaiting() : 0];
    if (parts.includes('error')) return 'error';
    if (parts.includes(null)) return null;
    return (parts as number[]).reduce((s, n) => s + n, 0);
  });

  /** At most four, in priority order, only those the user may see. */
  protected readonly kpis = computed<Kpi[]>(() => {
    const all: (Kpi & { permitted: boolean })[] = [
      { key: 'actions', labelKey: 'dashboard.openActions', route: '/inbox', value: this.openActions(), attention: true, permitted: this.canReadJobs },
      { key: 'approvals', labelKey: 'dashboard.awaitingApproval', route: this.canApproveInvoices ? '/finance/invoices' : this.canApprovePurchaseOrders ? '/finance/purchase-orders' : '/finance/vendor-bills',
        value: this.awaitingApproval(), attention: true, permitted: this.canSeeApprovals },
      { key: 'operation', labelKey: 'dashboard.inOperation', route: this.canReadLive ? '/operations' : '/jobs', value: this.inOperation(), attention: false,
        permitted: this.canReadJobs },
      { key: 'overdue', labelKey: 'dashboard.overdueInvoices', route: '/finance/receivables', value: this.overdueInvoices(), attention: true,
        permitted: this.canReadInvoices },
      { key: 'closed', labelKey: 'dashboard.closedJobs', route: '/jobs', value: this.closedJobs(), attention: false, permitted: this.canReadJobs },
    ];
    return all.filter((k) => k.permitted).slice(0, 4)
      .map((k) => ({ key: k.key, labelKey: k.labelKey, route: k.route, value: k.value, attention: k.attention && typeof k.value === 'number' && k.value > 0 }));
  });

  protected stepOf(status: string): number { return STATUS_STEP[status] ?? 0; }
  protected stageLabel(status: string): string {
    const step = this.stepOf(status);
    if (!step) return status === 'ON_HOLD' ? 'On hold' : status === 'CANCELLED' ? 'Cancelled' : status;
    return step > FLOW_STEPS.length ? 'Closed' : `Step ${step} · ${FLOW_STEPS[step - 1]}`;
  }

  private load<T>(path: string, query: Record<string, QueryValue>, permitted: boolean): Observable<T> {
    return permitted
      ? this.http.get<T>(`${this.base}/${path}`, { params: toHttpParams(query), context: new HttpContext().set(SILENT_ERRORS, true) })
      : of(null as T);
  }

  private signal<T>(request: Observable<T>): Signal<Loaded<T>> {
    return toSignal(request.pipe(catchError(() => of('error' as const))), { initialValue: null }) as Signal<Loaded<T>>;
  }

  private count(path: string, filters: Record<string, QueryValue>, permitted: boolean): Signal<Loaded<number>> {
    return this.signal(this.load<Page<unknown> | null>(path, { ...filters, page: 0, size: 1 }, permitted)
      .pipe(map((page) => page?.totalElements ?? 0)));
  }
}
