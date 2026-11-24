import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LookupService } from '../../core/lookups/lookup.service';
import { JobTechnicalComponent } from './job-technical.component';
import { JobDocument, JobRequest, JobTechnicalJob, JobTechnicalService } from './job-technical.service';

describe('JobTechnicalComponent', () => {
  it('clears the old job and cancels its detail and write requests on job change', () => {
    const firstJob = new Subject<JobTechnicalJob>();
    const secondJob = new Subject<JobTechnicalJob>();
    const firstRequest = new Subject<JobRequest>();
    const firstDocuments = new Subject<JobDocument[]>();
    const oldWrite = new Subject<JobRequest>();
    TestBed.configureTestingModule({ providers: [
      provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => false, currentUser: () => null } },
      { provide: LookupService, useValue: { documentTypes: () => of([]) } },
      { provide: JobTechnicalService, useValue: {
        job: (id: string) => id === 'first' ? firstJob : secondJob,
        request: () => firstRequest,
        documents: () => firstDocuments,
        voyageFields: () => of([]), review: () => of(null), checklists: () => of([]), templates: () => of([]),
        initialise: () => oldWrite,
      } },
    ] });
    TestBed.overrideComponent(JobTechnicalComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobTechnicalComponent);
    fixture.componentRef.setInput('jobId', 'first');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    firstJob.next({ id: 'first', jobNumber: 'A', vessels: [] });
    comp['startRequest']();
    expect(firstRequest.observed).toBe(true);
    expect(oldWrite.observed).toBe(true);

    fixture.componentRef.setInput('jobId', 'second');
    fixture.detectChanges();
    expect(firstRequest.observed).toBe(false);
    expect(firstDocuments.observed).toBe(false);
    expect(oldWrite.observed).toBe(false);
    expect(comp['job']()).toBeNull();
    expect(comp['request']()).toBeNull();
    expect(comp['busy']()).toBe(false);
    oldWrite.next({ id: 'old', jobId: 'first', status: 'DRAFT', templateVersion: null, items: [], version: null });
    expect(comp['request']()).toBeNull();
    secondJob.next({ id: 'second', jobNumber: 'B', vessels: [] });
    expect(comp['job']()?.jobNumber).toBe('B');
    fixture.destroy();
  });
});
