import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { OfflineEventsService } from './offline-events.service';

describe('device event drafts', () => {
  it('shows unreadable drafts without replacing their stored data', () => {
    localStorage.clear();
    localStorage.setItem('s2s.offline.events.field-user', '{broken');
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { currentUser: signal({ id: 'field-user' }) } }] });
    const queue = TestBed.inject(OfflineEventsService);
    expect(queue.drafts()).toEqual([]);
    expect(queue.storageError()).toBe(true);
    expect(() => queue.save('job', { typeCode: 'ALL_FAST', occurredAt: null, jobVesselId: null,
      startedAt: null, endedAt: null, remarks: null, source: 'LIVE' })).toThrow();
    expect(localStorage.getItem('s2s.offline.events.field-user')).toBe('{broken');
    localStorage.clear();
  });

  it('retains failed saves and isolates another account while reusing the original capture identity', () => {
    localStorage.clear();
    const user = signal({ id: 'field-user' });
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { currentUser: user } }] });
    const queue = TestBed.inject(OfflineEventsService);
    const http = TestBed.inject(HttpTestingController);
    const input = { typeCode: 'ALL_FAST', occurredAt: '2026-10-18T10:00:00Z', jobVesselId: null,
      startedAt: null, endedAt: null, remarks: null, source: 'PAPER_LOG' };
    const draft = queue.save('job', input);
    queue.sync(draft).subscribe({ error: (error: unknown) => expect(error).toBeInstanceOf(HttpErrorResponse) });
    const request = http.expectOne('/api/v1/jobs/job/operations/events');
    expect(request.request.body.clientEventId).toBe(draft.id);
    request.flush({ code: 'OFFLINE_EVENT_CONFLICT', detail: 'Review the paper log' }, { status: 422, statusText: 'Conflict' });
    expect(queue.drafts()[0].error).toBe('Review the paper log');
    user.set({ id: 'another-user' });
    expect(queue.drafts()).toEqual([]);
    user.set({ id: 'field-user' });
    const edited = queue.save('job', { ...input, remarks: 'Checked' }, draft);
    expect(edited.input.deviceRecordedAt).toBe(draft.input.deviceRecordedAt);
    queue.sync(edited, 'Reviewed').subscribe();
    const retry = http.expectOne('/api/v1/jobs/job/operations/events');
    expect(retry.request.body.clientEventId).toBe(draft.id);
    retry.flush({ id: 'server-event' });
    expect(queue.drafts()).toEqual([]);
    http.verify();
    localStorage.clear();
  });
});
