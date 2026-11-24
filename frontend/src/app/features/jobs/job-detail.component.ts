import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Message } from 'primeng/message';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { API_BASE_URL, SILENT_ERRORS, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { FLOW_STEPS } from '../../core/sts-flow';
import { JobDetail, JobService, JobTariffTermsInput } from './job.service';
import { JobWorkflowComponent } from './job-workflow.component';

interface StepOperations {
  vessels: { current: { etaUtc: string } | null }[];
  events: { status: string; typeCode: string; interval?: boolean; endedAt?: string | null }[];
  eventTypes: { code: string; active: boolean }[];
  incidents: { status: string }[];
  sofRevisions?: { status: string }[];
  cargo?: { completed: boolean }[];
}
interface StepClock { exceeded: boolean; clock: { startAt: string | null; elapsedHours: number | null; basisHours: number; final: boolean } }
interface StepRecovery { status: string; assets: { status: string }[] }
interface StepFdaRevision { id: string; revisionNo: number; status: string; number?: string }

/** Job status order, used when a step's own record is not readable by this user. */
const ORDER = ['OPEN', 'DOCUMENTS_PENDING', 'PLANNING', 'APPROVED_FOR_READINESS', 'MOBILISING', 'IN_PROGRESS', 'DEMOBILISING',
  'REPORT_PENDING', 'FINANCIAL_CLOSING', 'CLOSED'];

export interface FlowStep {
  no: number; title: string; who: string; detail: string; done: boolean;
  route: unknown[] | null; extra?: { label: string; route: unknown[] }[];
  remaining?: string | null;
}

/** The job page is the Ship2Ship STS flow: ten steps from enquiry to bringing the equipment back, then report and FDA. */
@Component({
  selector: 's2s-job-detail',
  imports: [DecimalPipe, FormsModule, RouterLink, TranslatePipe, Message, PageHeaderComponent, StatusTagComponent, MoneyPipe, UtcDateTimePipe, JobWorkflowComponent],
  templateUrl: './job-detail.component.html',
  styleUrl: './job-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobDetailComponent {
  private readonly service = inject(JobService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private loadRequests = new Subscription();
  private loadedId = '';

  readonly id = input.required<string>();

  protected readonly job = signal<JobDetail | null>(null);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly ops = signal<StepOperations | null>(null);
  protected readonly clock = signal<StepClock | null>(null);
  protected readonly recovery = signal<StepRecovery | null>(null);
  protected readonly fdaList = signal<StepFdaRevision[] | null>(null);
  protected readonly canEditTariffTerms = computed(() => this.auth.hasPermission(Permissions.masterFinanceWrite));
  protected readonly editingTariffTerms = signal(false);
  protected readonly savingTariffTerms = signal(false);
  protected readonly tariffTermsError = signal<string | null>(null);
  protected tariffTerms: { billingIncrementMinutes: number | null; graceMinutes: number | null;
    roundingRule: JobTariffTermsInput['roundingRule'] | ''; reason: string } = {
      billingIncrementMinutes: null, graceMinutes: null, roundingRule: '', reason: '',
    };

  private can(permission: string): boolean { return this.auth.hasPermission(permission); }

  protected editTariffTerms(): void {
    const tariff = this.job()?.tariff;
    if (!tariff) return;
    this.tariffTerms = { billingIncrementMinutes: tariff.billingIncrementMinutes,
      graceMinutes: tariff.graceMinutes, roundingRule: tariff.roundingRule ?? '', reason: '' };
    this.tariffTermsError.set(null);
    this.editingTariffTerms.set(true);
  }

  protected saveTariffTerms(): void {
    const job = this.job();
    const terms = this.tariffTerms;
    if (!job || !this.canEditTariffTerms() || this.savingTariffTerms() || terms.billingIncrementMinutes === null ||
      terms.graceMinutes === null || !terms.roundingRule || !terms.reason.trim()) return;
    this.savingTariffTerms.set(true);
    this.tariffTermsError.set(null);
    this.service.updateTariffTerms(job, { billingIncrementMinutes: terms.billingIncrementMinutes,
      graceMinutes: terms.graceMinutes, roundingRule: terms.roundingRule, reason: terms.reason.trim() })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => { this.savingTariffTerms.set(false); this.editingTariffTerms.set(false); this.reload(); },
        error: (error: unknown) => { this.savingTariffTerms.set(false);
          this.tariffTermsError.set(toProblem(error).detail || 'Unable to save billing terms.'); },
      });
  }

  protected remainingTab(item: string): string | null {
    if (item.includes('cargo reading')) return 'cargo';
    if (item.includes('SOF')) return 'sof';
    if (item.includes('incident') && this.can(Permissions.opsIncidentRead)) return 'incidents';
    if (item.includes('FDA') || item.includes('final account')) return 'fda';
    return null;
  }

  protected remainingRoute(step: FlowStep, item: string): unknown[] | null {
    if (item === 'Finish Marine readiness above')
      return this.steps().find(candidate => candidate.no < step.no && !candidate.done && candidate.route)?.route ?? null;
    return step.route;
  }

  /** Status reached or passed, for steps whose own record this user cannot read. */
  private passed(status: string): boolean {
    const current = ORDER.indexOf(this.job()?.status ?? '');
    return current >= ORDER.indexOf(status);
  }

  protected readonly steps = computed<FlowStep[]>(() => {
    const job = this.job();
    if (!job) return [];
    const id = job.id;
    const enquiry = this.can(Permissions.enquiryRead);
    const progress = job.flow;
    const jpo = progress.jpoStatus;
    const jpoComplete = jpo === 'DONE' || jpo === 'ISSUED' || jpo === 'SUPERSEDED';
    const acceptances = progress.jpoAcceptanceCount;
    const plan = progress.mooringStatus;
    const ops = this.ops();
    const etas = ops ? ops.vessels.map(v => v.current?.etaUtc ?? null) : job.vessels.map(v => v.etaUtc ?? null);
    const recorded = new Set((ops?.events ?? []).filter(e => e.status === 'ACTIVE').map(e => e.typeCode));
    const checklist = (ops?.eventTypes ?? []).filter(t => t.active && t.code !== 'OTHER');
    const openIncidents = (ops?.incidents ?? []).filter(i => i.status !== 'CLOSED').length;
    const allIncidents = ops?.incidents.length ?? 0;
    const recovery = this.recovery();
    const operationDone = this.passed('DEMOBILISING');
    const opsLeft: string[] = [];
    const unticked = checklist.filter(t => !recorded.has(t.code));
    if (unticked.length) opsLeft.push(`${unticked.length} checklist step${unticked.length > 1 ? 's' : ''}`);
    if ((ops?.events ?? []).some(e => e.status === 'ACTIVE' && e.interval && !e.endedAt)) opsLeft.push('Complete hose connection');
    if ((ops?.cargo ?? []).some(c => !c.completed)) opsLeft.push('Final cargo reading');
    if (ops && !(ops.sofRevisions ?? []).some(s => s.status === 'ISSUED')) opsLeft.push('Prepare & issue SOF');
    if (!operationDone && !opsLeft.length) opsLeft.push(this.passed('APPROVED_FOR_READINESS') ? 'Waiting for operation completion' :
      progress.preArrivalTotal > progress.preArrivalReviewed || jpo && (!jpoComplete || acceptances < 3) || plan && plan !== 'ISSUED' && plan !== 'SUPERSEDED'
        ? 'Finish Marine readiness above' : 'Awaiting readiness approval');
    return [
      { no: 1, title: FLOW_STEPS[0], who: 'Commercial', detail: job.enquiryReference, done: true,
        route: enquiry ? ['/commercial/enquiries', job.enquiryId] : null },
      { no: 2, title: FLOW_STEPS[1], who: 'Commercial', detail: 'Expenses estimated', done: true,
        route: enquiry ? ['/commercial/enquiries', job.enquiryId, 'estimate'] : null },
      { no: 3, title: FLOW_STEPS[2], who: 'Commercial', detail: `Accepted · ${job.quotationReference}`, done: true,
        route: enquiry && this.can(Permissions.quotationRead) ? ['/commercial/enquiries', job.enquiryId, 'quotation'] : null },
      { no: 4, title: FLOW_STEPS[3], who: 'Marine',
        detail: progress.documentTotal ? `${progress.documentReceived} of ${progress.documentTotal} received · ${progress.preArrivalReviewed} of ${progress.preArrivalTotal} pre-arrival reviewed` : 'Send the request to both vessels',
        done: progress.preArrivalTotal > 0 ? progress.preArrivalReviewed === progress.preArrivalTotal : this.passed('APPROVED_FOR_READINESS'),
        remaining: progress.preArrivalTotal > progress.preArrivalReviewed ? `${progress.preArrivalTotal - progress.preArrivalReviewed} pre-arrival review${progress.preArrivalTotal - progress.preArrivalReviewed > 1 ? 's' : ''} pending` :
          (!progress.preArrivalTotal && !this.passed('APPROVED_FOR_READINESS') ? 'Waiting for required documents' : null),
        route: progress.canOpenDocuments ? ['/jobs', id, 'technical'] : null },
      { no: 5, title: FLOW_STEPS[4], who: 'Marine', detail: jpo ? `${this.label(jpo)} · ${acceptances} of 3 acceptances` : 'Not started',
        done: jpo ? jpoComplete && acceptances === 3 : this.passed('APPROVED_FOR_READINESS'),
        remaining: !jpoComplete ? 'Complete JPO' : acceptances < 3 ? `${3 - acceptances} JPO acceptance${3 - acceptances > 1 ? 's' : ''} pending` : null,
        route: progress.canOpenJpo ? ['/jobs', id, 'jpo'] : null,
        extra: progress.canOpenWeather ? [{ label: 'Weather', route: ['/jobs', id, 'weather'] }] : [] },
      { no: 6, title: FLOW_STEPS[5], who: 'Marine', detail: plan ? this.label(plan) : 'Not started',
        done: plan ? plan === 'ISSUED' || plan === 'SUPERSEDED' : this.passed('APPROVED_FOR_READINESS'),
        remaining: plan !== 'ISSUED' && plan !== 'SUPERSEDED' ? 'Issue mooring plan' : (!this.passed('APPROVED_FOR_READINESS') ? 'Awaiting readiness approval' : null),
        route: progress.canOpenMooring ? ['/jobs', id, 'mooring-plan'] : null },
      { no: 7, title: FLOW_STEPS[6], who: 'Operations',
        detail: etas.every(Boolean) ? 'Both ETAs known' : `${etas.filter(Boolean).length} of ${etas.length} ETAs known`,
        done: etas.length > 0 && etas.every(Boolean) || this.passed('MOBILISING'),
        remaining: !etas.every(Boolean) ? `${etas.filter(e => !e).length} vessel ETA${etas.filter(e => !e).length > 1 ? 's' : ''} missing` : (!this.passed('MOBILISING') ? 'Waiting for readiness approval' : null),
        route: this.can(Permissions.opsLiveRead) ? ['/jobs', id, 'operations'] : null },
      { no: 8, title: FLOW_STEPS[7], who: 'Operations',
        detail: checklist.length ? `${checklist.filter(t => recorded.has(t.code)).length} of ${checklist.length} steps ticked` : 'Fenders, hoses, mooring master, tugs and cargo times',
        done: operationDone, route: this.can(Permissions.opsLiveRead) ? ['/jobs', id, 'operations'] : null,
        remaining: !operationDone ? opsLeft.join(' · ') : null,
        extra: [
          ...(this.can(Permissions.opsAllocationRead) ? [{ label: 'Equipment and team', route: ['/jobs', id, 'resources'] }] : []),
          ...(this.can(Permissions.opsMovementRead) ? [{ label: 'Tug and clock', route: ['/jobs', id, 'movements'] }] : []),
        ] },
      { no: 9, title: FLOW_STEPS[8], who: 'Operations',
        detail: !ops ? 'Recorded during the operation' : openIncidents ? `${openIncidents} open` : allIncidents ? 'All closed' : 'No incidents',
        done: operationDone && openIncidents === 0,
        remaining: openIncidents ? `${openIncidents} open incident${openIncidents > 1 ? 's' : ''} to resolve` : (!operationDone ? 'Awaiting operation completion' : null),
        route: this.can(Permissions.opsLiveRead) ? ['/jobs', id, 'operations'] : null },
      { no: 10, title: FLOW_STEPS[9], who: 'Operations',
        detail: !recovery ? 'After the operation' : recovery.status !== 'OPEN' ? 'Closed'
          : recovery.assets.length ? `${recovery.assets.filter(a => a.status !== 'UNRECORDED').length} of ${recovery.assets.length} items checked` : 'No equipment sent yet',
        done: recovery ? recovery.status !== 'OPEN' : this.passed('REPORT_PENDING'),
        remaining: recovery && recovery.status === 'OPEN' ? 'Record POAC disembarkation and close recovery' : (!this.passed('REPORT_PENDING') ? 'Waiting for required documents' : null),
        route: this.can(Permissions.opsRecoveryRead) ? ['/jobs', id, 'recovery'] : null },
      { no: 11, title: FLOW_STEPS[10], who: 'Operations', detail: 'Events, cargo times and incidents in one report',
        done: progress.finalReportIssued,
        remaining: !progress.finalReportIssued ? 'Issue final operation report' : null,
        route: this.can(Permissions.reportFinalRead) ? ['/jobs', id, 'final-report'] : null },
      (() => {
        const fdaList = this.fdaList();
        const fda = fdaList?.[0];
        const fdaDone = fda ? (fda.status === 'APPROVED' || fda.status === 'ISSUED') : this.passed('CLOSED');
        const fdaDetail = fda
          ? `${fda.number ?? ('Revision ' + fda.revisionNo)} · ${this.label(fda.status)}`
          : 'Client invoices, vendor bills and final account';
        const fdaRemaining = fdaDone
          ? null
          : fda
            ? (fda.status === 'SUBMITTED' ? 'Awaiting FDA approval' : 'Submit FDA for approval')
            : (!this.passed('CLOSED') ? 'Prepare and approve the final account' : null);
        return {
          no: 12, title: FLOW_STEPS[11], who: 'Operations prepares · Finance approves bills and invoices · Management approves final account',
          detail: fdaDetail,
          done: fdaDone,
          remaining: fdaRemaining,
          route: this.auth.hasAnyPermission([Permissions.finCostRead, Permissions.finFdaRead, Permissions.finInvoiceRead]) ? ['/jobs', id, 'finance'] : null,
        };
      })(),
    ];
  });

  /** The first step not done yet. */
  protected readonly nextStep = computed(() => this.steps().find(s => !s.done)?.no ?? null);
  protected readonly doneCount = computed(() => this.steps().filter(s => s.done).length);
  protected readonly activeRemaining = computed(() => {
    const current = this.steps().find(s => s.no === this.nextStep());
    return current?.remaining ? { title: current.title, remaining: current.remaining, route: current.route } : null;
  });

  constructor() {
    this.destroyRef.onDestroy(() => this.loadRequests.unsubscribe());
    effect(() => {
      this.id();
      untracked(() => this.reload());
    });
  }

  protected reload(): void {
    this.loadRequests.unsubscribe();
    this.loadRequests = new Subscription();
    const id = this.id();
    if (this.loadedId !== id) {
      this.loadedId = id;
      this.editingTariffTerms.set(false);
      this.tariffTermsError.set(null);
      this.job.set(null);
      this.ops.set(null);
      this.clock.set(null);
      this.recovery.set(null);
      this.fdaList.set(null);
    }
    this.loadRequests.add(this.service.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (job) => { this.job.set(job); this.errorKey.set(null); },
      error: () => this.errorKey.set('errors.loadFailed'),
    }));
    this.optional(Permissions.opsLiveRead, `${id}/operations`, this.ops);
    this.optional(Permissions.opsMovementRead, `${id}/movements/clock`, this.clock);
    this.optional(Permissions.opsRecoveryRead, `${id}/recovery`, this.recovery);
    this.optional(Permissions.finFdaRead, `${id}/fda`, this.fdaList);
  }

  /** Step detail is a nice-to-have: without permission or on error the step falls back on the job status. */
  private optional<T>(permission: string, path: string, target: { set(value: T | null): void }): void {
    if (this.can(permission)) {
      this.loadRequests.add(this.http.get<T>(`${this.base}/jobs/${path}`, { context: new HttpContext().set(SILENT_ERRORS, true) })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (x) => target.set(x), error: () => target.set(null) }));
    } else {
      target.set(null);
    }
  }

  private label(status: string): string {
    return status.charAt(0) + status.slice(1).toLowerCase();
  }
}
