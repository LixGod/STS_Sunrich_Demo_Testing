import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { inferOperationType, toControlPath } from './enquiry-form.component';
import { EnquiryRequest, EnquiryService, fromLocalDateTime, newIdempotencyKey, toLocalDateTime } from './enquiry.service';

describe('enquiry helpers', () => {
  it('keeps the wall-clock ETA the user picked, independent of the browser time zone', () => {
    const picked = new Date(2026, 9, 20, 8, 5);
    expect(toLocalDateTime(picked)).toBe('2026-10-20T08:05:00');
    expect(fromLocalDateTime('2026-10-20T08:05:00')?.getHours()).toBe(8);
    expect(toLocalDateTime(null)).toBeNull();
  });

  it('maps server field paths onto form controls', () => {
    expect(toControlPath('clientId')).toBe('client');
    expect(toControlPath('motherVessel.vesselId')).toBe('mother.vessel');
    expect(toControlPath('cargo[1].unitId')).toBe('cargo.1.unitId');
  });

  it('infers an operation type from selected cargo grade categories, falling back to OTHER when mixed', () => {
    expect(inferOperationType([])).toBeNull();
    expect(inferOperationType(['LPG', 'LPG'])).toBe('LPG');
    expect(inferOperationType(['LNG', 'CHEMICAL'])).toBe('OTHER');
    expect(inferOperationType(['PETROLEUM', 'LPG', 'CHEMICAL'])).toBe('OTHER');
    expect(inferOperationType(['LPG', null])).toBeNull();
    expect(inferOperationType(['UNKNOWN'])).toBeNull();
  });

  it('creates distinct idempotency keys', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});

describe('EnquiryService', () => {
  it('sends the idempotency key on create and the version on transitions', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(EnquiryService);
    const backend = TestBed.inject(HttpTestingController);

    service.create({} as EnquiryRequest, 'key-1').subscribe();
    expect(backend.expectOne('/api/v1/enquiries').request.headers.get('Idempotency-Key')).toBe('key-1');

    service.transition('e1', 3, 'LOST', 'Late').subscribe();
    const transition = backend.expectOne('/api/v1/enquiries/e1/transitions');
    expect(transition.request.headers.get('If-Match')).toBe('"3"');
    expect(transition.request.body).toEqual({ to: 'LOST', reason: 'Late' });
    expect(service.xlsxUrl({ status: 'COSTING', q: 'MUM', branchId: null })).toBe('/api/v1/enquiries/xlsx?status=COSTING&q=MUM');
    backend.verify();
  });
});
