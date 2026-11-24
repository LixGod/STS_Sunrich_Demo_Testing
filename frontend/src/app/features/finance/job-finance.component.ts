import { HttpClient } from '@angular/common/http';
import { formatDate } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Observable, Subscription } from 'rxjs';
import { API_BASE_URL, FieldProblem, Page, ifMatch, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { FinanceService, formatMoney, invoiceTotals } from './finance.service';
import { JobInvoicesComponent } from './job-invoices.component';

interface CostLine { costHeadCode: string; description: string; quantity: string; unitPrice: string }
interface CostInput {
  kind: string; vendorId: string; reference: string; documentDate: string; dueDate: string | null; currency: string;
  lines: CostLine[]; fileId: string | null; purchaseOrderId: string | null;
  baseRate: string | null; jobRate: string | null; baseRateSource: string | null; jobRateSource: string | null; notes: string;
}
interface Issue { code: string; message: string }
interface CostDocument extends CostInput {
  id: string; number: string | null; vendorName: string; status: string; grossAmount: string; baseAmount: string; baseCurrency: string;
  jobAmount: string; jobCurrency: string; submittedBy: string | null; version: number; decisionReason: string | null;
  baseRateDate: string | null; jobRateDate: string | null;
  warnings: Issue[];
}
interface Summary {
  jobNumber: string; currency: string; budget: string; committed: string; actualNet: string; actualTax: string; variance: string; warnings: Issue[];
  heads: { code: string; name: string; budget: string; committed: string; actualNet: string; actualTax: string; variance: string; varianceToCommitment: string }[];
}
interface Options {
  vendors: { id: string; name: string }[];
  heads: { code: string; name: string }[];
  currencies: { code: string; name: string; minorUnits: number }[];
  purchaseOrders: { id: string; number: string | null; reference: string; vendorId: string }[]; jobCurrency: string; baseCurrency: string;
  estimateLines?: { costHeadCode: string; description: string; quantity: string; unitPrice: string }[];
}
interface FdaInput { reconciliationNote: string; noExternalCostsReason: string; notes: string; extraHoursNote: string; adjustments: { kind: string; description: string; amount: string; fileId: string }[] }
interface Fda { id: string; revisionNo: number; number: string | null; status: string; content: FdaInput; snapshot: string | null; version: number; submittedBy: string | null; reason: string | null }
interface Snapshot { income: string; expenses: string; received: string; outstanding: string; margin: string }
interface FdaPreview { clientOnAccount: string; snapshot: Snapshot & { billing: { status: string }[]; bills: unknown[] } }
/** The parts of the job and its charge clock the finance tabs show. */
interface JobRef { enquiryId: string; quotationReference: string; currency: string; quotedAmount: number }
interface ChargeClock {
  exceeded: boolean;
  notice: { status: string; deliveryOutcome: string | null } | null;
  clock: { startAt: string | null; stopAt: string | null; basisHours: number; elapsedHours: number | null; extraHours: number | null;
    tariffTermsStatus: string; billableExtraUnits: number | null; additionalCharge: number | null; currency: string | null; final: boolean };
}
interface FinanceData {
  costs: Page<CostDocument>; summary: Summary | null; options: Options | null; fda: Fda[]; preview: FdaPreview | null;
  job: JobRef | null; clock: ChargeClock | null;
}

/** Finance tabs follow the accepted quote, time charge, invoicing, actual costs and FDA. */
const TABS = [
  { key: 'estimate', icon: 'pi pi-calculator', permission: Permissions.finCostRead },
  { key: 'extraHours', icon: 'pi pi-clock', permission: Permissions.opsMovementRead },
  { key: 'invoices', icon: 'pi pi-file', permission: Permissions.finInvoiceRead },
  { key: 'expenses', icon: 'pi pi-money-bill', permission: Permissions.finCostRead },
  { key: 'fda', icon: 'pi pi-wallet', permission: Permissions.finFdaRead },
];

@Component({
  selector: 's2s-job-finance',
  imports: [FormsModule, RouterLink, TranslatePipe, StatusTagComponent, DocumentViewerComponent, ReasonDialogComponent, UtcDateTimePipe, JobInvoicesComponent],
  templateUrl: './job-finance.component.html',
  styleUrl: './job-finance.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobFinanceComponent {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly finance = inject(FinanceService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);
  readonly jobId = input.required<string>();
  /** Selected tab (query parameter); an unknown or hidden tab falls back to the first one the user may see. */
  readonly tab = input<string>();
  protected readonly tabs = computed(() => { this.auth.currentUser(); return TABS.filter((t) => this.auth.hasPermission(t.permission)); });
  protected readonly active = computed(() => this.tabs().find((t) => t.key === this.tab())?.key
    ?? ((this.canApprove() || this.canApprovePo()) && !this.canWrite() ? 'expenses' : this.tabs()[0]?.key));
  protected readonly data = signal<FinanceData | null>(null);
  protected readonly loadingSections = signal<(keyof FinanceData)[]>([]);
  protected readonly loadErrors = signal<(keyof FinanceData)[]>([]);
  protected readonly fieldErrors = signal<FieldProblem[]>([]);
  protected readonly selectedFda = signal<Fda | null>(null);
  protected readonly evidence = signal<ViewableFile | null>(null);
  protected readonly snapshot = computed<Snapshot | null>(() => {
    const value = this.selectedFda()?.snapshot;
    return value ? JSON.parse(value) as Snapshot : null;
  });
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly formatMoney = formatMoney;
  protected readonly canWrite = computed(() => this.auth.hasPermission('FIN.COST.WRITE'));
  protected readonly canApprove = computed(() => this.auth.hasPermission('FIN.COST.APPROVE'));
  protected readonly canApprovePo = computed(() => this.auth.hasPermission(Permissions.finCostPoApprove));
  protected readonly canWriteFda = computed(() => this.auth.hasPermission('FIN.FDA.WRITE'));
  protected readonly canApproveFda = computed(() => this.auth.hasPermission('FIN.FDA.APPROVE'));
  protected readonly actor = computed(() => this.auth.currentUser()?.id);
  protected readonly canReadEstimate = computed(() => this.auth.hasPermission(Permissions.estimateRead));
  protected readonly canInvoice = computed(() => this.auth.hasPermission(Permissions.finInvoiceWrite));
  protected canApproveDocument(doc: CostDocument): boolean { return doc.kind === 'PO' ? this.canApprovePo() : this.canApprove(); }
  protected readonly revenueHeads = signal<{ code: string; name: string }[]>([]);
  protected extraCostHead = '';
  protected editor: CostInput | null = null;
  protected editing: CostDocument | null = null;
  protected fdaInput: FdaInput = { reconciliationNote: '', noExternalCostsReason: '', notes: '', extraHoursNote: '', adjustments: [] };
  protected decisionReason = '';
  protected vendorQuery = '';
  protected page = 0;
  private costKey = crypto.randomUUID();
  private fdaKey = crypto.randomUUID();
  private loadedJobId = '';
  private jobVersion = 0;
  private reloadRequest?: Subscription;

  constructor() {
    effect(() => {
      const id = this.jobId();
      untracked(() => { if (id !== this.loadedJobId) this.resetForJob(id); this.reload(); });
    });
    if (this.canInvoice()) this.finance.options().pipe(takeUntilDestroyed()).subscribe({
      next: (o) => this.revenueHeads.set(o.costHeads.filter((h) => h.kind !== 'EXPENSE')), error: () => this.revenueHeads.set([]),
    });
  }
  protected url(path: string): string { return `${this.base}/jobs/${this.jobId()}/${path}`; }
  private resetForJob(id: string): void {
    this.loadedJobId = id;
    this.jobVersion++;
    this.data.set(null);
    this.editor = null;
    this.editing = null;
    this.selectedFda.set(null);
    this.evidence.set(null);
    this.fdaInput = { reconciliationNote: '', noExternalCostsReason: '', notes: '', extraHoursNote: '', adjustments: [] };
    this.pendingAction = null;
    this.reasonVisible.set(false);
    this.page = 0;
    this.vendorQuery = '';
    this.error.set('');
    this.fieldErrors.set([]);
    this.busy.set(false);
    this.costKey = crypto.randomUUID();
    this.fdaKey = crypto.randomUUID();
  }
  protected reload(): void {
    this.reloadRequest?.unsubscribe();
    this.reloadRequest = new Subscription();
    const selectedId = this.selectedFda()?.id;
    this.selectedFda.set(null);
    this.data.set(null);
    this.loadingSections.set([]);
    this.loadErrors.set([]);
    const read = this.auth.hasPermission('FIN.COST.READ');
    if (read) {
      this.loadPart('costs', this.http.get<Page<CostDocument>>(this.url(`costs?page=${this.page}&size=25`)));
      this.loadPart('summary', this.http.get<Summary>(this.url('costs/summary')));
    }
    if (this.canWrite()) this.loadPart('options', this.http.get<Options>(this.url(`costs/options?q=${encodeURIComponent(this.vendorQuery)}`)));
    if (this.auth.hasPermission('FIN.FDA.READ')) {
      this.loadPart('fda', this.http.get<Fda[]>(this.url('fda')), (rows) => {
        const selected = rows.find((row) => row.id === selectedId) ?? rows[0];
        if (selected) this.selectFda(selected);
      });
      this.loadPart('preview', this.http.get<FdaPreview>(this.url('fda/preview')));
    }
    this.loadPart('job', this.http.get<JobRef>(`${this.base}/jobs/${this.jobId()}`));
    if (this.auth.hasPermission(Permissions.opsMovementRead)) this.loadPart('clock', this.http.get<ChargeClock>(this.url('movements/clock')));
  }

  private loadPart<K extends keyof FinanceData>(key: K, request: Observable<FinanceData[K]>, loaded?: (value: FinanceData[K]) => void): void {
    this.loadingSections.update((items) => [...items, key]);
    this.reloadRequest?.add(request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => { this.data.update((data) => ({ ...(data ?? this.emptyData()), [key]: value })); loaded?.(value); },
      error: () => {
        this.loadErrors.update((items) => [...items, key]);
        this.loadingSections.update((items) => items.filter((item) => item !== key));
      },
      complete: () => this.loadingSections.update((items) => items.filter((item) => item !== key)),
    }));
  }

  private emptyData(): FinanceData {
    return { costs: { items: [], page: this.page, size: 25, totalElements: 0 }, summary: null, options: null,
      fda: [], preview: null, job: null, clock: null };
  }
  protected edit(doc?: CostDocument): void {
    this.editing = doc ?? null;
    this.costKey = crypto.randomUUID();
    this.editor = doc ? {
      kind: doc.kind, vendorId: doc.vendorId, reference: doc.reference, documentDate: doc.documentDate, dueDate: doc.dueDate,
      currency: doc.currency, lines: doc.lines.map(({ costHeadCode, description, quantity, unitPrice }) => ({ costHeadCode, description, quantity, unitPrice })),
      fileId: doc.fileId, purchaseOrderId: doc.purchaseOrderId,
      baseRate: doc.baseRate, jobRate: doc.jobRate,
      baseRateSource: doc.baseRateSource ?? '', jobRateSource: doc.jobRateSource ?? '', notes: doc.notes ?? '',
    } : {
      kind: 'BILL', vendorId: '', reference: '', documentDate: formatDate(new Date(), 'yyyy-MM-dd', 'en'), dueDate: null,
      currency: this.data()?.options?.jobCurrency ?? this.data()?.summary?.currency ?? 'USD', lines: [], fileId: null,
      purchaseOrderId: null, baseRate: null, jobRate: null, baseRateSource: '', jobRateSource: '', notes: '',
    };
    if (!doc) this.addLine();
  }
  protected addLine(): void { this.editor?.lines.push({ costHeadCode: '', description: '', quantity: '1', unitPrice: '' }); }
  protected importFromQuote(): void {
    const lines = this.data()?.options?.estimateLines;
    if (!lines || !lines.length || !this.editor) return;
    if (this.editor.lines.some(line => line.costHeadCode || line.description || line.unitPrice || line.quantity !== '1')
      && !window.confirm(this.translate.instant('jobFinance.replaceQuoteLines'))) return;
    this.editor.lines = lines.map((l) => ({
      costHeadCode: l.costHeadCode,
      description: l.description,
      quantity: l.quantity,
      unitPrice: l.unitPrice,
    }));
  }
  protected chooseHead(line: CostLine, code: string): void {
    const heads = this.data()?.options?.heads ?? [];
    const previous = heads.find(head => head.code === line.costHeadCode)?.name;
    if (!line.description || line.description === previous) line.description = heads.find(head => head.code === code)?.name ?? '';
    line.costHeadCode = code;
  }
  protected costMinorUnits(): number {
    return this.data()?.options?.currencies.find((c) => c.code === this.editor?.currency)?.minorUnits ?? 2;
  }
  protected costTotals() {
    return invoiceTotals((this.editor?.lines ?? []).map((line) => ({
      quantity: line.quantity, unitPrice: line.unitPrice, rate: null,
    })), this.costMinorUnits());
  }
  protected saveCost(): void {
    if (!this.editor) return;
    const currency = this.editor.currency.toUpperCase();
    const payload = { ...this.editor,
      baseRate: currency === this.data()?.options?.baseCurrency ? null : this.editor.baseRate,
      jobRate: currency === this.data()?.options?.jobCurrency ? null : this.editor.jobRate,
      baseRateSource: currency === this.data()?.options?.baseCurrency ? null : this.editor.baseRateSource,
      jobRateSource: currency === this.data()?.options?.jobCurrency ? null : this.editor.jobRateSource,
    };
    const request = this.editing
      ? this.http.put(this.url(`costs/${this.editing.id}`), payload, { headers: { 'If-Match': ifMatch(this.editing.version) } })
      : this.http.post(this.url('costs'), payload, { headers: { 'Idempotency-Key': this.costKey } });
    this.run(request, () => { this.editor = null; this.editing = null; });
  }
  protected readonly reasonVisible = signal(false);
  private pendingAction: { doc?: CostDocument; decision: string; fda?: boolean } | null = null;
  protected openReasonAction(doc: CostDocument, decision: string): void {
    this.pendingAction = { doc, decision };
    this.reasonVisible.set(true);
  }
  protected openFdaReasonAction(decision: string): void {
    this.pendingAction = { decision, fda: true };
    this.reasonVisible.set(true);
  }
  protected onReasonConfirmed(reason: string | null): void {
    const target = this.pendingAction;
    this.pendingAction = null;
    this.reasonVisible.set(false);
    if (!reason) return;
    if (target?.doc) {
      this.costAction(target.doc, target.decision, reason);
    } else if (target?.fda) {
      this.fdaAction(target.decision, reason);
    }
  }
  protected costAction(doc: CostDocument, decision: string, reason = this.decisionReason): void {
    this.run(this.http.post(this.url(`costs/${doc.id}/actions`), { decision, reason },
      { headers: { 'If-Match': ifMatch(doc.version) } }), () => { this.decisionReason = ''; });
  }
  protected upload(event: Event, apply: (id: string) => void): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    const problem = uploadProblem(file);
    if (problem) { this.error.set(problem); (event.target as HTMLInputElement).value = ''; return; }
    const body = new FormData(); body.append('file', file);
    this.run(this.http.post<{ id: string }>(this.url('costs/files'), body), (value) => apply(value.id), false);
  }
  protected uploadCost(event: Event): void { this.upload(event, (id) => { if (this.editor) this.editor.fileId = id; }); }
  protected viewEvidence(id: string): void {
    this.http.get<{ id: string; originalName: string; contentType: string }>(`${this.base}/files/${id}`)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (file) => this.evidence.set({ id: file.id, name: file.originalName, contentType: file.contentType }),
        error: (error: unknown) => this.failed(error),
      });
  }
  protected uploadAdjustment(event: Event, index: number): void { this.upload(event, (id) => { this.fdaInput.adjustments[index].fileId = id; }); }
  protected selectFda(row: Fda): void { this.selectedFda.set(row); this.fdaInput = { ...structuredClone(row.content), extraHoursNote: row.content.extraHoursNote ?? '' }; }
  protected fieldError(field: string): string | null { return this.fieldErrors().find((e) => e.field === field)?.message ?? null; }
  protected issuedInvoices(preview: FdaPreview): number { return preview.snapshot.billing.filter((invoice) => invoice.status === 'ISSUED').length; }
  protected createFda(): void { this.run(this.http.post<Fda>(this.url('fda'), null, { headers: { 'Idempotency-Key': this.fdaKey } }), (row) => { this.selectFda(row); this.fdaKey = crypto.randomUUID(); }); }
  protected saveFda(): void {
    const row = this.selectedFda(); if (!row) return;
    this.run(this.http.put<Fda>(this.url(`fda/${row.id}`), this.fdaInput, { headers: { 'If-Match': ifMatch(row.version) } }), (value) => this.selectFda(value));
  }
  protected fdaAction(decision: string, reason = this.decisionReason): void {
    const row = this.selectedFda(); if (!row) return;
    this.run(this.http.post(this.url(`fda/${row.id}/actions`), { decision, reason },
      { headers: { 'If-Match': ifMatch(row.version) } }), () => { this.decisionReason = ''; });
  }
  /** Drafts the invoice for the finalized extra hours (priced from the confirmed tariff terms) and opens it. */
  protected invoiceExtraHours(): void {
    this.run(this.finance.extraHourInvoice(this.jobId(), this.extraCostHead),
      (invoice) => void this.router.navigate(['/finance/invoices', invoice.id]), false);
  }
  protected addAdjustment(): void { this.fdaInput.adjustments.push({ kind: 'EXPENSE', description: '', amount: '', fileId: '' }); }
  private run<T>(request: Observable<T>, next?: (value: T) => void, reload = true): void {
    if (this.busy()) return;
    const version = this.jobVersion;
    this.busy.set(true); this.error.set(''); this.fieldErrors.set([]);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => { if (version !== this.jobVersion) return; this.busy.set(false); next?.(value); if (reload) this.reload(); },
      error: (error: unknown) => { if (version === this.jobVersion) this.failed(error); },
    });
  }
  private failed(error: unknown): void {
    this.busy.set(false);
    const problem = toProblem(error);
    this.fieldErrors.set(problem.errors ?? []);
    const fieldMsg = problem.errors?.map((e) => e.message || `${e.field}: required`).filter(Boolean).join('. ');
    this.error.set(fieldMsg || (problem.code === 'VALIDATION_FAILED' && problem.detail ? problem.detail : problemKey(problem)));
  }
}
