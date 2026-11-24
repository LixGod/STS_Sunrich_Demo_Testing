import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { JobWorkflowComponent } from './job-workflow.component';

describe('JobWorkflowComponent', () => {
  it('syncs completed stages without showing a manual stage control', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService()] });
    const fixture = TestBed.createComponent(JobWorkflowComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('/api/v1/jobs/job-1/workflow/sync');
    expect(request.request.method).toBe('POST');
    request.flush({ status: 'DOCUMENTS_PENDING', revision: 1, version: 3, readinessRequestedBy: null,
      closureRequestedBy: null, canRequest: false, canOverride: false, history: [],
      actions: [{ to: 'PLANNING', reasonRequired: false, blockers: [] }] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#job-next-state')).toBeNull();
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    http.verify();
  });

  it('offers approval only after a colleague requests it', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService()] });
    const fixture = TestBed.createComponent(JobWorkflowComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const workflow = { status: 'PLANNING', revision: 1, version: 3, readinessRequestedBy: 'another-user',
      closureRequestedBy: null, canRequest: false, canOverride: false, history: [],
      actions: [{ to: 'APPROVED_FOR_READINESS', reasonRequired: false, blockers: [] }] };
    http.expectOne('/api/v1/jobs/job-1/workflow/sync').flush(workflow);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('button[type="submit"]').click();
    const transition = http.expectOne('/api/v1/jobs/job-1/workflow/transitions');
    expect(transition.request.body).toEqual({ to: 'APPROVED_FOR_READINESS', reason: '', overrideReason: '' });
    transition.flush({ ...workflow, status: 'APPROVED_FOR_READINESS', actions: [] });
    http.verify();
  });
});
