import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { QuotationService, QuotationVersion, addWorkingDays } from './quotation.service';

describe('working-day quotation validity', () => {
  it('skips Saturday and Sunday when deriving the valid-until date', () => {
    expect(addWorkingDays(new Date(2026, 10, 13), 5)).toBe('2026-11-20');
  });
});

describe('QuotationService owner rejection', () => {
  it('posts the selected issued version with optimistic locking', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(QuotationService);
    const http = TestBed.inject(HttpTestingController);
    service.rejectByOwner({ id: 'quote-1', version: 3 } as QuotationVersion).subscribe();
    const request = http.expectOne('/api/v1/quotation-versions/quote-1/owner-rejection');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    request.flush({ versions: [] });
    http.verify();
  });

  it('builds a single Proforma PDF URL without a separate INR endpoint parameter', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(QuotationService);
    expect(service.pdfUrl({ id: 'quote-1' } as QuotationVersion)).toBe('/api/v1/quotation-versions/quote-1/pdf');
  });
});
