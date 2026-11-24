import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import Big from 'big.js';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, ifMatch, toHttpParams } from '../../core/api/api.models';

export type InvoiceStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'ISSUED';
export type InvoiceAction = 'EDIT' | 'SUBMIT' | 'APPROVE' | 'RETURN' | 'ISSUE' | 'CREDIT';
export type DocType = 'INVOICE' | 'CREDIT_NOTE';
/** Money arrives as a JSON number with the currency's decimals; it is only displayed or fed to big.js, never added as a number. */
export type Money = string | number;

export interface InvoiceLine {
  lineNo: number; source: 'QUOTATION' | 'MANUAL'; costHeadCode: string; costHeadName: string; description: string | null; quantity: Money;
  unitPrice: Money; amount: Money; taxCode: string | null; taxRate: Money | null; taxAmount: Money; extraHours: boolean;
}

export interface FinanceIssue { code: string; message: string }

export interface Invoice {
  id: string; docType: DocType; number: string | null; status: InvoiceStatus; jobId: string; jobNumber: string; clientId: string;
  clientName: string | null; creditedInvoiceId: string | null; creditedInvoiceNumber: string | null; currency: string; minorUnits: number;
  invoiceDate: string; dueDate: string | null; billingName: string; billingAddress: string | null; paymentTerms: string | null; notes: string | null;
  lines: InvoiceLine[]; netAmount: Money; taxAmount: Money; grossAmount: Money; creditedAmount: Money; receivedAmount: Money; outstandingAmount: Money;
  blockers: FinanceIssue[]; submittedByName: string | null; submittedAt: string | null; decidedByName: string | null; decidedAt: string | null;
  returnedReason: string | null; issuedByName: string | null; issuedAt: string | null; pdfSha256: string | null; allowedActions: InvoiceAction[];
  version: number;
}

export interface InvoiceSummary {
  id: string; docType: DocType; number: string | null; status: InvoiceStatus; jobId: string; jobNumber: string; clientName: string | null;
  currency: string; invoiceDate: string; dueDate: string | null; grossAmount: Money; outstandingAmount: Money; overdueDays: number | null;
  minorUnits: number;
}

export interface InvoiceLineInput {
  source: 'QUOTATION' | 'MANUAL'; costHeadCode: string; description: string | null; quantity: string; unitPrice: string; extraHours?: boolean;
}

export interface InvoiceInput {
  invoiceDate: string; dueDate: string | null; billingName: string; billingAddress: string | null; paymentTerms: string | null; notes: string | null;
  lines: InvoiceLineInput[];
}

export interface CostHeadOption { code: string; name: string; kind: string }
export interface InvoiceOptions { costHeads: CostHeadOption[] }

export interface JobInvoices {
  jobId: string; jobNumber: string; currency: string; invoices: InvoiceSummary[]; canCreate: boolean; hasAcceptedQuotation: boolean;
}

export interface Allocation { id: string; invoiceId: string; invoiceNumber: string | null; amount: Money; reversed: boolean }

export interface Receipt {
  id: string; clientId: string; clientName: string | null; receivedOn: string; amount: Money; currency: string;
  method: 'BANK_TRANSFER' | 'OTHER'; reference: string | null; status: 'ACTIVE' | 'REVERSED'; allocatedAmount: Money; onAccountAmount: Money;
  allocations: Allocation[]; recordedByName: string | null; recordedAt: string; reversalReason: string | null; canWrite: boolean; minorUnits: number;
  version: number;
}

export interface ReceiptParty {
  clientId: string; clientName: string | null; currency: string; outstandingAmount: Money;
  minorUnits: number;
}

export interface AllocationInput { invoiceId: string; amount: string }

export interface ReceiptInput {
  clientId: string; receivedOn: string; amount: string; currency: string; method: 'BANK_TRANSFER' | 'OTHER';
  reference: string | null; allocations: AllocationInput[];
}

export type Bucket = 'NOT_DUE' | 'D1_30' | 'D31_60' | 'D61_90' | 'D90_PLUS';
export const BUCKETS: Bucket[] = ['NOT_DUE', 'D1_30', 'D31_60', 'D61_90', 'D90_PLUS'];

export interface ReceivableRow {
  invoiceId: string; number: string | null; clientId: string; clientName: string | null; jobNumber: string; currency: string; invoiceDate: string;
  dueDate: string | null; grossAmount: Money; creditedAmount: Money; receivedAmount: Money; outstandingAmount: Money; overdueDays: number; bucket: Bucket;
  minorUnits: number;
}
export interface ReceivableTotal { clientName: string | null; currency: string; buckets: Record<Bucket, Money>; total: Money; minorUnits: number }
export interface Receivables { asOf: string; rows: ReceivableRow[]; totals: ReceivableTotal[] }
export interface ReceivablesPage { asOf: string; rows: Page<ReceivableRow>; totals: ReceivableTotal[] }

export interface InvoiceFilters { status?: InvoiceStatus | null; docType?: DocType | null; overdue?: boolean; q?: string }

/**
 * Live line and document totals for the editor, exactly with big.js. Mirrors the server: amount = quantity × unit price and
 * tax = amount × rate / 100, each half up to the currency's minor units. The server's figures remain authoritative.
 */
export function invoiceTotals(
  lines: { quantity: string; unitPrice: string; rate: Money | null }[], minorUnits: number,
): { lines: { amount: Big; tax: Big }[]; net: Big; tax: Big; gross: Big } {
  const out = lines.map((l) => {
    const amount = toBig(l.quantity).times(toBig(l.unitPrice)).round(minorUnits, Big.roundHalfUp);
    const tax = l.rate === null ? new Big(0) : amount.times(toBig(l.rate)).div(100).round(minorUnits, Big.roundHalfUp);
    return { amount, tax };
  });
  const net = out.reduce((s, l) => s.plus(l.amount), new Big(0));
  const tax = out.reduce((s, l) => s.plus(l.tax), new Big(0));
  return { lines: out, net, tax, gross: net.plus(tax) };
}

/** A user-entered or server decimal as big.js; anything that is not a number counts as zero. */
export function toBig(value: Money | null | undefined): Big {
  try {
    return new Big(String(value ?? '').trim() || 0);
  } catch {
    return new Big(0);
  }
}

/** Money with exactly the currency's decimals, e.g. `12,538.48`. */
export function formatMoney(value: Money | Big, minorUnits = 2): string {
  const fixed = (value instanceof Big ? value : toBig(value)).toFixed(minorUnits);
  const [whole, fraction] = fixed.split('.');
  const sign = whole.startsWith('-') ? '-' : '';
  const digits = whole.replace('-', '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${digits}${fraction ? '.' + fraction : ''}`;
}

/** Local calendar date (YYYY-MM-DD) formatted from the device's local calendar, avoiding UTC day-shift bugs. */
export function localToday(d: Date = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private headers(v: { version: number }) { return { headers: { 'If-Match': ifMatch(v.version) } }; }

  // Invoices
  invoices(filters: InvoiceFilters, page: number, size: number): Observable<Page<InvoiceSummary>> {
    return this.http.get<Page<InvoiceSummary>>(`${this.base}/finance/invoices`, {
      params: toHttpParams({ status: filters.status, docType: filters.docType, overdue: filters.overdue || null, q: filters.q?.trim(), page, size }),
    });
  }
  jobInvoices(jobId: string): Observable<JobInvoices> { return this.http.get<JobInvoices>(`${this.base}/jobs/${jobId}/invoices`); }
  extraHourInvoice(jobId: string, costHeadCode: string): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base}/jobs/${jobId}/invoices/extra-hours`, { costHeadCode });
  }
  create(jobId: string, fromQuotation: boolean): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base}/jobs/${jobId}/invoices`, { fromQuotation });
  }
  invoice(id: string): Observable<Invoice> { return this.http.get<Invoice>(`${this.base}/finance/invoices/${id}`); }
  options(): Observable<InvoiceOptions> { return this.http.get<InvoiceOptions>(`${this.base}/finance/invoices/options`); }
  save(invoice: Invoice, input: InvoiceInput): Observable<Invoice> {
    return this.http.put<Invoice>(`${this.base}/finance/invoices/${invoice.id}`, input, this.headers(invoice));
  }
  action(invoice: Invoice, action: 'submit' | 'issue'): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base}/finance/invoices/${invoice.id}/${action}`, null, this.headers(invoice));
  }
  decide(invoice: Invoice, decision: 'APPROVE' | 'RETURN', reason: string | null): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base}/finance/invoices/${invoice.id}/decision`, { decision, reason }, this.headers(invoice));
  }
  creditNote(invoice: Invoice): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base}/finance/invoices/${invoice.id}/credit-notes`, null);
  }
  pdfUrl(id: string): string { return `${this.base}/finance/invoices/${id}/pdf`; }

  // Receipts
  receipts(status: string | null, q: string, page: number, size: number): Observable<Page<Receipt>> {
    return this.http.get<Page<Receipt>>(`${this.base}/finance/receipts`, { params: toHttpParams({ status, q: q.trim(), page, size }) });
  }
  parties(): Observable<ReceiptParty[]> { return this.http.get<ReceiptParty[]>(`${this.base}/finance/receipts/parties`); }
  openInvoices(clientId: string, currency: string): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(`${this.base}/finance/receipts/open-invoices`, { params: toHttpParams({ clientId, currency }) });
  }
  recordReceipt(input: ReceiptInput): Observable<Receipt> { return this.http.post<Receipt>(`${this.base}/finance/receipts`, input); }
  allocate(receipt: Receipt, allocations: AllocationInput[]): Observable<Receipt> {
    return this.http.post<Receipt>(`${this.base}/finance/receipts/${receipt.id}/allocations`, { allocations }, this.headers(receipt));
  }
  reverse(receipt: Receipt, reason: string): Observable<Receipt> {
    return this.http.post<Receipt>(`${this.base}/finance/receipts/${receipt.id}/reverse`, { reason }, this.headers(receipt));
  }

  // Receivables
  receivables(asOf: string | null): Observable<Receivables> {
    return this.http.get<Receivables>(`${this.base}/finance/receivables`, { params: toHttpParams({ asOf }) });
  }
  receivablesPage(asOf: string | null, page: number): Observable<ReceivablesPage> {
    return this.http.get<ReceivablesPage>(`${this.base}/finance/receivables/page`, { params: toHttpParams({ asOf, page, size: 25 }) });
  }
  receivablesXlsxUrl(asOf: string | null): string { return `${this.base}/finance/receivables/xlsx${asOf ? `?asOf=${asOf}` : ''}`; }
}

export interface CostDocumentRow {
  id: string; jobId: string; jobNumber: string; kind: 'PO' | 'BILL'; number: string | null; reference: string; vendorName: string; status: string;
  documentDate: string; dueDate: string | null; currency: string; grossAmount: Money; jobCurrency: string; jobAmount: Money;
}

export interface ProfitabilityRow {
  jobId: string; jobNumber: string; clientName: string | null; status: string; currency: string; minorUnits: number; jobDate: string;
  incomeNet: Money; costNet: Money; adjustmentsNet: Money; margin: Money; marginPercent: Money | null; fdaIssued: boolean;
}

export interface ProfitabilityFilters { status?: string | null; from?: string | null; to?: string | null }

/** Cross-job finance lists (purchase orders, vendor bills, profitability). */
@Injectable({ providedIn: 'root' })
export class FinanceListsService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  costDocuments(kind: 'PO' | 'BILL', status: string | null, q: string, page: number, size: number): Observable<Page<CostDocumentRow>> {
    return this.http.get<Page<CostDocumentRow>>(`${this.base}/finance/cost-documents`, { params: toHttpParams({ kind, status, q: q.trim(), page, size }) });
  }
  profitability(filters: ProfitabilityFilters, page: number, size: number): Observable<Page<ProfitabilityRow>> {
    return this.http.get<Page<ProfitabilityRow>>(`${this.base}/finance/profitability`, { params: toHttpParams({ ...filters, page, size }) });
  }
  profitabilityXlsxUrl(filters: ProfitabilityFilters): string {
    return `${this.base}/finance/profitability/xlsx?${toHttpParams({ ...filters }).toString()}`;
  }
}
