import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { LookupService } from './lookup.service';

describe('LookupService', () => {
  it('does not reuse a previous account’s cached options', async () => {
    const user = signal({ id: 'first' });
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { currentUser: user } }] });
    const http = TestBed.inject(HttpTestingController);
    const service = TestBed.inject(LookupService);
    const first = firstValueFrom(service.currencies());
    http.expectOne((request) => request.url.includes('/masters/currencies'))
      .flush({ items: [{ code: 'USD', name: 'Dollar' }], page: 0, size: 100, totalElements: 1 });
    expect((await first)[0].value).toBe('USD');
    user.set({ id: 'second' });
    const second = firstValueFrom(service.currencies());
    http.expectOne((request) => request.url.includes('/masters/currencies'))
      .flush({ items: [{ code: 'EUR', name: 'Euro' }], page: 0, size: 100, totalElements: 1 });
    expect((await second)[0].value).toBe('EUR');
    user.set({ id: 'second' });
    const relogin = firstValueFrom(service.currencies());
    http.expectOne((request) => request.url.includes('/masters/currencies'))
      .flush({ items: [{ code: 'GBP', name: 'Pound' }], page: 0, size: 100, totalElements: 1 });
    expect((await relogin)[0].value).toBe('GBP');
    http.verify();
  });

  it('loads currencies across every API page', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const result = firstValueFrom(TestBed.inject(LookupService).currencies());
    const rows = Array.from({ length: 100 }, (_, i) => ({ code: `C${String(i).padStart(2, '0')}`, name: `Currency ${i}` }));

    http.expectOne((request) => request.urlWithParams.includes('page=0') && request.url.includes('/masters/currencies'))
      .flush({ items: rows, page: 0, size: 100, totalElements: 101 });
    http.expectOne((request) => request.urlWithParams.includes('page=1') && request.url.includes('/masters/currencies'))
      .flush({ items: [{ code: 'ZZZ', name: 'Currency 100' }], page: 1, size: 100, totalElements: 101 });

    expect((await result).length).toBe(101);
    http.verify();
  });
});
