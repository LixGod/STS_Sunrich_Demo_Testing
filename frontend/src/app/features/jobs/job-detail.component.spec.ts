import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { JobDetailComponent } from './job-detail.component';

const emptyFlow = {
  documentReceived: 0, documentTotal: 0, preArrivalReviewed: 0, preArrivalTotal: 0,
  jpoStatus: null, jpoAcceptanceCount: 0, mooringStatus: null, finalReportIssued: false,
  canOpenDocuments: false, canOpenJpo: false, canOpenMooring: false, canOpenWeather: false,
};

describe('JobDetailComponent', () => {
  it('lets STS Admin save job-specific extra-hour terms with a reason', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideTranslateService(), provideRouter([]),
      { provide: AuthService, useValue: {
        hasPermission: (p: string) => p === Permissions.masterFinanceWrite,
        hasAnyPermission: () => false, currentUser: () => null,
      } },
    ] });
    TestBed.overrideComponent(JobDetailComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobDetailComponent);
    fixture.componentRef.setInput('id', 'job-terms');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const job = { id: 'job-terms', version: 2, status: 'PLANNING', vessels: [], cargo: [], enquiryReference: 'ENQ',
      quotationReference: 'QUO', enquiryId: 'enq', flow: emptyFlow,
      tariff: { billingIncrementMinutes: null, graceMinutes: null, roundingRule: null, extraHourRate: 20000 } };
    http.expectOne('/api/v1/jobs/job-terms').flush(job);
    fixture.componentInstance['editTariffTerms']();
    fixture.componentInstance['tariffTerms'] = {
      billingIncrementMinutes: 15, graceMinutes: 0, roundingRule: 'ROUND_UP_TO_INCREMENT', reason: 'Owner confirmed terms',
    };
    fixture.componentInstance['saveTariffTerms']();
    const save = http.expectOne('/api/v1/jobs/job-terms/tariff-terms');
    expect(save.request.method).toBe('PUT');
    expect(save.request.headers.get('If-Match')).toBe('"2"');
    expect(save.request.body).toEqual(fixture.componentInstance['tariffTerms']);
    save.flush({ ...job, version: 3, tariff: { ...job.tariff, billingIncrementMinutes: 15, graceMinutes: 0, roundingRule: 'ROUND_UP_TO_INCREMENT' } });
    http.expectOne('/api/v1/jobs/job-terms').flush({ ...job, version: 3 });
    fixture.destroy();
    http.verify();
  });
  it('marks the report step done when issued while the job is still demobilising', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideTranslateService(), provideRouter([]),
      { provide: AuthService, useValue: { hasPermission: () => false, hasAnyPermission: () => false, currentUser: () => null } },
    ] });
    TestBed.overrideComponent(JobDetailComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobDetailComponent);
    fixture.componentRef.setInput('id', 'job-report');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/jobs/job-report').flush({
      id: 'job-report', status: 'DEMOBILISING', vessels: [], cargo: [], enquiryReference: 'ENQ',
      quotationReference: 'QUO', enquiryId: 'enq', flow: { ...emptyFlow, finalReportIssued: true },
    });
    const report = fixture.componentInstance['steps']().find(step => step.no === 11);
    expect(report?.done).toBe(true);
    expect(report?.remaining).toBeNull();
    fixture.destroy();
    http.verify();
  });
  it('shows completed Marine steps from the job overview without Marine access', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideTranslateService(), provideRouter([]),
      { provide: AuthService, useValue: {
        hasPermission: (p: string) => p === Permissions.jobRead,
        hasAnyPermission: () => false, currentUser: () => null,
      } },
    ] });
    TestBed.overrideComponent(JobDetailComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobDetailComponent);
    fixture.componentRef.setInput('id', 'job-1');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/jobs/job-1').flush({
      id: 'job-1', status: 'PLANNING', vessels: [], cargo: [], enquiryReference: 'ENQ-1',
      quotationReference: 'QUO-1', enquiryId: 'enq-1', flow: {
        documentReceived: 22, documentTotal: 22, preArrivalReviewed: 22, preArrivalTotal: 22,
        jpoStatus: 'DONE', jpoAcceptanceCount: 3, mooringStatus: 'ISSUED',
        canOpenDocuments: false, canOpenJpo: false, canOpenMooring: false, canOpenWeather: false,
      },
    });
    const steps = fixture.componentInstance['steps']();
    expect(steps.filter(step => [4, 5, 6].includes(step.no)).map(step => step.done)).toEqual([true, true, true]);
    expect(steps.filter(step => [4, 5, 6].includes(step.no)).map(step => step.route)).toEqual([null, null, null]);
    fixture.destroy();
    http.verify();
  });

  it('cancels the previous job load when the route ID changes', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideTranslateService(), provideRouter([]),
      { provide: AuthService, useValue: { hasPermission: () => false, hasAnyPermission: () => false, currentUser: () => null } },
    ] });
    const fixture = TestBed.createComponent(JobDetailComponent);
    fixture.componentRef.setInput('id', 'first');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const first = http.expectOne('/api/v1/jobs/first');

    fixture.componentRef.setInput('id', 'second');
    fixture.detectChanges();
    expect(first.cancelled).toBe(true);
    expect(fixture.componentInstance['job']()).toBeNull();
    const second = http.expectOne('/api/v1/jobs/second');
    second.flush({ id: 'second', jobNumber: 'JOB-2', status: 'OPEN', vessels: [], cargo: [],
      enquiryReference: 'ENQ-2', quotationReference: 'QUO-2', enquiryId: 'enq-2', flow: emptyFlow });
    expect(fixture.componentInstance['job']()?.id).toBe('second');
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  it('marks step 12 done when FDA is approved or issued', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService(),
        provideRouter([]),
        { provide: AuthService, useValue: { hasPermission: () => true, hasAnyPermission: () => true, currentUser: () => null } },
      ],
    });
    const fixture = TestBed.createComponent(JobDetailComponent);
    fixture.componentRef.setInput('id', 'job-123');
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/jobs/job-123').flush({
      id: 'job-123', jobNumber: 'WFN-5484', status: 'FINANCIAL_CLOSING',
      vessels: [], cargo: [], quotationReference: 'Q-1', enquiryReference: 'E-1', enquiryId: 'enq-1', flow: emptyFlow,
    });
    http.expectOne('/api/v1/jobs/job-123/operations').flush(null);
    http.expectOne('/api/v1/jobs/job-123/movements/clock').flush(null);
    http.expectOne('/api/v1/jobs/job-123/recovery').flush(null);
    http.expectOne('/api/v1/jobs/job-123/fda').flush([
      { id: 'fda-1', revisionNo: 1, status: 'APPROVED', number: 'FDA-001' },
    ]);

    fixture.detectChanges();
    const syncReq = http.match('/api/v1/jobs/job-123/workflow/sync');
    if (syncReq.length) {
      syncReq[0].flush({
        status: 'FINANCIAL_CLOSING', revision: 1, version: 1, readinessRequestedBy: null,
        closureRequestedBy: null, canRequest: true, canOverride: false, actions: [], history: [],
      });
    }

    fixture.detectChanges();
    const steps = fixture.componentInstance['steps']();
    const step12 = steps.find((s) => s.no === 12);

    expect(step12?.done).toBe(true);
    expect(step12?.remaining).toBeNull();
    expect(step12?.detail).toBe('FDA-001 · Approved');

    // JobWorkflow sync completion triggers a reload; flush or match open requests
    http.match(() => true).forEach((r) => r.flush(null));
    http.verify();
  });
});
