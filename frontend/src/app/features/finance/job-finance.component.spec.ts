import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { JobFinanceComponent } from './job-finance.component';

describe('JobFinanceComponent', () => {
  it('does not offer extra-hour invoicing while tariff terms are pending', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: {
        hasPermission: (permission: string) => ['FIN.INVOICE.WRITE', 'OPS.MOVEMENT.READ'].includes(permission),
        currentUser: () => null,
      } },
    ] });
    const fixture = TestBed.createComponent(JobFinanceComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.componentRef.setInput('tab', 'extraHours');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/finance/invoices/options').flush({ costHeads: [{ code: 'EXTRA', name: 'Extra hours', kind: 'REVENUE' }] });
    http.expectOne('/api/v1/jobs/job-1').flush({ enquiryId: 'enq-1' });
    http.expectOne('/api/v1/jobs/job-1/movements/clock').flush({ exceeded: true, notice: null,
      clock: { startAt: '2026-10-25T00:00:00Z', stopAt: '2026-10-28T00:00:00Z', basisHours: 48,
        elapsedHours: 72, extraHours: 24, billableExtraUnits: null, additionalCharge: null,
        tariffTermsStatus: 'PENDING_TARIFF_TERMS', currency: 'INR', final: true } });
    fixture.detectChanges();
    const invoiceButton = [...fixture.nativeElement.querySelectorAll('button')].find((button: HTMLButtonElement) =>
      button.textContent?.includes('finance.fromClock')) as HTMLButtonElement | undefined;
    expect(invoiceButton?.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('jobFinance.extra.pendingTerms');
    fixture.destroy();
    http.verify();
  });
  it('keeps available finance sections when the FDA list fails', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: {
        hasPermission: (permission: string) => permission === 'FIN.COST.READ' || permission === 'FIN.FDA.READ',
        currentUser: () => null,
      } },
    ] });
    TestBed.overrideComponent(JobFinanceComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobFinanceComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/jobs/job-1/costs?page=0&size=25').flush({ items: [], page: 0, size: 25, totalElements: 0 });
    http.expectOne('/api/v1/jobs/job-1/costs/summary').flush({ jobNumber: 'JOB-1', currency: 'USD', heads: [], warnings: [] });
    http.expectOne('/api/v1/jobs/job-1/fda/preview').flush({ clientOnAccount: '0', snapshot: { billing: [], bills: [] } });
    http.expectOne('/api/v1/jobs/job-1').flush({ enquiryId: 'enq-1' });
    http.expectOne('/api/v1/jobs/job-1/fda').flush({ code: 'UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    expect(fixture.componentInstance['data']()?.summary?.jobNumber).toBe('JOB-1');
    expect(fixture.componentInstance['loadErrors']()).toContain('fda');
    fixture.destroy();
    http.verify();
  });

  it('clears a draft and ignores old requests when the job changes', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: {
        hasPermission: (permission: string) => permission === 'FIN.COST.READ' || permission === 'FIN.COST.WRITE',
        currentUser: () => null,
      } },
    ] });
    TestBed.overrideComponent(JobFinanceComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobFinanceComponent);
    fixture.componentRef.setInput('jobId', 'first');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const first = http.match(request => request.url.includes('/jobs/first'));
    expect(first).toHaveLength(4);

    fixture.componentInstance['edit']();
    expect(fixture.componentInstance['editor']).not.toBeNull();
    fixture.componentInstance['saveCost']();
    const oldSave = http.expectOne('/api/v1/jobs/first/costs');
    fixture.componentRef.setInput('jobId', 'second');
    fixture.detectChanges();

    expect(first.every(request => request.cancelled)).toBe(true);
    expect(fixture.componentInstance['editor']).toBeNull();
    expect(fixture.componentInstance['data']()).toBeNull();
    expect(http.match(request => request.url.includes('/jobs/second'))).toHaveLength(4);
    fixture.componentInstance['edit']();
    oldSave.flush({});
    expect(fixture.componentInstance['editor']).not.toBeNull();
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  it('keeps entered cost lines when quote replacement is declined', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => false, currentUser: () => null } },
    ] });
    TestBed.overrideComponent(JobFinanceComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobFinanceComponent);
    const comp = fixture.componentInstance;
    comp['data'].set({ options: { estimateLines: [{ costHeadCode: 'QUOTE', description: 'Quote', quantity: '1', unitPrice: '5' }] } } as never);
    comp['edit']();
    comp['editor']!.lines[0] = { costHeadCode: 'MANUAL', description: 'Manual', quantity: '2', unitPrice: '10' };
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    comp['importFromQuote']();
    expect(comp['editor']!.lines[0].costHeadCode).toBe('MANUAL');
    expect(confirm).toHaveBeenCalledOnce();
    confirm.mockReturnValue(true);
    comp['importFromQuote']();
    expect(comp['editor']!.lines[0].costHeadCode).toBe('QUOTE');
    confirm.mockRestore();
    fixture.destroy();
  });
});
