import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { DashboardComponent } from './dashboard.component';

const page = (totalElements: number, items: unknown[] = []) => ({ items, page: 0, size: items.length || 1, totalElements });

describe('DashboardComponent', () => {
  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideTranslateService()],
    });
    await TestBed.compileComponents();
  });

  function signIn(permissions: string[], roleCode?: string) {
    TestBed.inject(AuthService).login('u', 'p').subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/login').flush({
      id: 'u', fullName: 'User', workEmail: 'u@example.com', language: 'en', permissions,
      grants: roleCode ? [{ roleCode, branchId: null }] : [],
    });
  }

  const STATUS_COUNTS: Record<string, number> = { OPEN: 1, DOCUMENTS_PENDING: 1, IN_PROGRESS: 2 };

  /** The requests every job reader's dashboard makes: grouped status counts, open actions, the flow board and the compact inbox. */
  function flushJobReader(http: HttpTestingController) {
    http.expectOne('/api/v1/alerts?state=OPEN&page=0&size=1').flush(page(3));
    http.expectOne('/api/v1/jobs/status-counts').flush({ ...STATUS_COUNTS, CLOSED: 7 });
    http.expectOne('/api/v1/alerts?page=0&size=5').flush(page(0));
  }

  it('shows the work waiting first, permitted key figures and the flow step of each job, and asks for nothing else', () => {
    signIn(['JOB.READ']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    flushJobReader(http);
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(12, [{ id: 'j1', jobNumber: 'JOB-1', status: 'OPEN', enquiryReference: 'ENQ-1', clientName: 'Client' }]));
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('JOB-1');
    expect(text).toContain('Step 4 · Documents from owners');
    expect(text).toContain('dashboard.openActions');
    expect(text).toContain('dashboard.inOperation');
    expect(text).not.toContain('dashboard.overdueInvoices');
    expect(fixture.nativeElement.querySelectorAll('.kpi').length).toBe(3);
    expect(fixture.nativeElement.querySelector('.kpi.attention')?.textContent).toContain('3');
    expect(fixture.nativeElement.querySelector('.stage-chart')).not.toBeNull();
    const summary: string = (fixture.componentInstance as unknown as { stageChart: () => { summary: string } }).stageChart().summary;
    expect(summary).toContain('4 · Documents from owners: 2');
    expect(summary).toContain('8 · STS operation: 2');
    expect(summary).toContain('On hold: 0');
    http.verify();
  });

  it('shows a failed count as unavailable instead of zero and totals receivables per currency', () => {
    signIn(['JOB.READ', 'FIN.INVOICE.READ']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    flushJobReader(http);
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(0));
    http.expectOne('/api/v1/finance/invoices?overdue=true&page=0&size=1').flush(null, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/v1/finance/receivables/summary').flush({ asOf: '2026-10-19', totals: [
      { clientName: 'A', currency: 'USD', minorUnits: 2, total: 1000, buckets: { NOT_DUE: 400, D1_30: 600, D31_60: 0, D61_90: 0, D90_PLUS: 0 } },
      { clientName: 'B', currency: 'USD', minorUnits: 2, total: 500.5, buckets: { NOT_DUE: 500.5, D1_30: 0, D31_60: 0, D61_90: 0, D90_PLUS: 0 } },
    ] });
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('dashboard.unavailable');
    expect(text).toContain('1,500.50');
    expect(text).toContain('600.00');
    http.verify();
  });

  it('offers an ageing chart only to users allowed to read invoices', () => {
    signIn(['FIN.INVOICE.READ']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/finance/invoices?overdue=true&page=0&size=1').flush(page(0));
    http.expectOne('/api/v1/finance/receivables/summary').flush({ asOf: '2026-10-26', totals: [
      { clientName: 'A', currency: 'USD', minorUnits: 2, total: 100, buckets: { NOT_DUE: 40, D1_30: 60, D31_60: 0, D61_90: 0, D90_PLUS: 0 } },
    ] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.receivables-chart')).not.toBeNull();
    http.verify();

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideTranslateService()],
    });
    signIn([]);
    const restricted = TestBed.createComponent(DashboardComponent);
    restricted.detectChanges();
    expect(restricted.nativeElement.querySelector('.receivables-chart')).toBeNull();
    TestBed.inject(HttpTestingController).verify();
  });

  it('does not show a failed approvals request as zero', () => {
    signIn(['JOB.READ', 'FIN.INVOICE.APPROVE']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    flushJobReader(http);
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(0));
    http.expectOne('/api/v1/finance/invoices?status=SUBMITTED&page=0&size=1').flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('dashboard.unavailable');
    http.verify();
  });

  it('shows scoped profitability to users who have no job or invoice access', () => {
    signIn(['FIN.PROFIT.READ']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/finance/profitability?page=0&size=5').flush(page(1, [{
      jobId: 'j1', jobNumber: 'JOB-1', clientName: 'Client', status: 'CLOSED', currency: 'USD', minorUnits: 2,
      incomeNet: '1200.00', costNet: '800.00', margin: '400.00', marginPercent: '33.3',
    }]));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('JOB-1');
    expect(fixture.nativeElement.textContent).toContain('USD 400.00');
    expect(fixture.nativeElement.querySelectorAll('.kpi').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('dashboard.recentProfitability');
    http.verify();
  });

  it('gives STS Admin a clear starting point without requesting job data', () => {
    signIn(['MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE'], 'STS_ADMIN');
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Set up vessels');
    expect(fixture.nativeElement.querySelector('a[href="/administration/vessels"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/administration/tariffs"]')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.kpi').length).toBe(0);
    TestBed.inject(HttpTestingController).verify();
  });

  it('sends a reviewer to the work queue instead of a write screen', () => {
    signIn(['JOB.READ', 'TECH.JOB.REVIEW']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    flushJobReader(http);
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(0));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/inbox"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Review and approvals');
    http.verify();
  });

  it('starts Finance with submitted vendor bills to approve', () => {
    signIn(['JOB.READ', 'FIN.INVOICE.READ', 'FIN.COST.READ', 'FIN.COST.APPROVE']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    flushJobReader(http);
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(0));
    http.expectOne('/api/v1/finance/invoices?overdue=true&page=0&size=1').flush(page(0));
    http.expectOne('/api/v1/finance/receivables/summary').flush({ asOf: '2026-10-21', totals: [] });
    http.expectOne('/api/v1/finance/cost-documents?status=SUBMITTED&kind=BILL&page=0&size=1').flush(page(0));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Approve vendor bills');
    expect(fixture.nativeElement.querySelector('a[href="/finance/vendor-bills"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/finance/purchase-orders"]')).toBeNull();
    http.verify();
  });

  it('counts purchase orders for a PO approver and shows zero for a missing operation status', () => {
    signIn(['JOB.READ', 'FIN.COST.READ', 'FIN.COST.PO.APPROVE']);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/alerts?state=OPEN&page=0&size=1').flush(page(0));
    http.expectOne('/api/v1/jobs/status-counts').flush({ OPEN: 1 });
    http.expectOne('/api/v1/alerts?page=0&size=5').flush(page(0));
    http.expectOne('/api/v1/jobs?page=0&size=8').flush(page(0));
    http.expectOne('/api/v1/finance/cost-documents?status=SUBMITTED&kind=PO&page=0&size=1').flush(page(2));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/finance/purchase-orders"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Approve purchase orders');
    expect(fixture.componentInstance['inOperation']()).toBe(0);
    expect(fixture.componentInstance['awaitingApproval']()).toBe(2);
    http.verify();
  });
});
