import { TestBed } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LookupService } from '../../core/lookups/lookup.service';
import { JobTechnicalComponent } from './job-technical.component';
import { JobDocument, JobRequest, JobTechnicalJob, JobTechnicalService, RequestItem } from './job-technical.service';

describe('JobTechnicalComponent', () => {
  it('files an inbox attachment only after its destination is selected', () => {
    const filed: string[] = [];
    const item = { id: 'item-1', jobVesselId: 'vessel-1', code: 'Q88', label: 'Q88', kind: 'DOCUMENT',
      documentTypeId: 'type-1', required: true, status: 'PENDING', responseText: null, reviewComment: null,
      documentVersionIds: [], version: 0, events: [] } as RequestItem;
    const request: JobRequest = { id: 'request-1', jobId: 'job-1', status: 'OPEN', templateVersion: null, items: [item], version: 0 };
    TestBed.configureTestingModule({ providers: [
      provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => null } },
      { provide: LookupService, useValue: { documentTypes: () => of([]) } },
      { provide: JobTechnicalService, useValue: {
        job: () => of({ id: 'job-1', jobNumber: 'JOB-1', vessels: [{ id: 'vessel-1', role: 'MOTHER', name: 'Ship A' }] }),
        request: () => of(request), documents: () => of([]), review: () => of(null),
        voyageFields: () => of([]), checklists: () => of([]), templates: () => of([]),
        voyage: () => of({ jobVesselId: 'vessel-1', versions: [] }),
        incomingMailStatus: () => of({ enabled: true }),
        incomingAttachments: () => of([{ id: 'mail-1', fileName: 'q88.pdf', contentType: 'application/pdf', sizeBytes: 100,
          sender: 'owner@example.test', subject: 'Documents', receivedAt: null }]),
        fileIncomingAttachment: (id: string, jobId: string, itemId: string) => {
          filed.push(`${id}:${jobId}:${itemId}`);
          return of({ id: 'doc-1' });
        },
      } },
    ] });
    TestBed.overrideComponent(JobTechnicalComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobTechnicalComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const row = component['incomingAttachments']()[0];
    component['fileIncomingAttachment'](row);
    expect(filed).toEqual([]);
    component['setMailTarget'](row.id, item.id);
    component['fileIncomingAttachment'](row);
    expect(filed).toEqual(['mail-1:job-1:item-1']);
    expect(component['incomingAttachments']()).toEqual([]);
    fixture.destroy();
  });

  it('uploads confirmed email attachments one at a time to their selected request items', () => {
    const uploaded: string[] = [];
    const item = { id: 'item-1', jobVesselId: null, code: 'OTHER', label: 'Owner document', kind: 'DOCUMENT',
      documentTypeId: 'type-1', required: true, status: 'PENDING', responseText: null, reviewComment: null,
      documentVersionIds: [], version: 0, events: [] } as RequestItem;
    const request: JobRequest = { id: 'request-1', jobId: 'job-1', status: 'OPEN', templateVersion: null, items: [item], version: 0 };
    TestBed.configureTestingModule({ providers: [
      provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => null } },
      { provide: LookupService, useValue: { documentTypes: () => of([]) } },
      { provide: JobTechnicalService, useValue: {
        job: () => of({ id: 'job-1', jobNumber: 'JOB-1', vessels: [] }),
        request: () => of(request), documents: () => of([]), review: () => of(null),
        incomingMailStatus: () => of({ enabled: false }),
        voyageFields: () => of([]), checklists: () => of([]), templates: () => of([]),
        upload: (_jobId: string, _item: unknown, file: File) => {
          uploaded.push(file.name);
          return of(new HttpResponse({ body: { id: file.name } }));
        },
      } },
    ] });
    TestBed.overrideComponent(JobTechnicalComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JobTechnicalComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component['queueDocuments']([
      new File(['%PDF-1.4'], 'first.pdf', { type: 'application/pdf' }),
      new File(['%PDF-1.4'], 'second.pdf', { type: 'application/pdf' }),
    ]);
    expect(component['canImportDocuments']()).toBe(false);
    component['setDocumentTarget'](0, 'item-1');
    component['setDocumentTarget'](1, 'item-1');
    component['importDocuments']();
    expect(uploaded).toEqual(['first.pdf', 'second.pdf']);
    expect(component['pendingDocuments']()).toEqual([]);
    expect(component['notice']()).toContain('2 documents uploaded');
    fixture.destroy();
  });

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
