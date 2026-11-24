import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { ReceivablesComponent } from './receivables.component';

describe('ReceivablesComponent', () => {
  it('requests one bounded page and moves to the next page', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
    ] });
    TestBed.overrideComponent(ReceivablesComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(ReceivablesComponent);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((request) => request.url === '/api/v1/finance/receivables/page'
      && request.params.get('page') === '0' && request.params.get('size') === '25')
      .flush({ asOf: '2026-10-27', rows: { items: [], page: 0, size: 25, totalElements: 26 }, totals: [] });
    fixture.componentInstance['goTo'](1);
    http.expectOne((request) => request.url === '/api/v1/finance/receivables/page' && request.params.get('page') === '1')
      .flush({ asOf: '2026-10-27', rows: { items: [], page: 1, size: 25, totalElements: 26 }, totals: [] });
    expect(fixture.componentInstance['data']()?.rows.page).toBe(1);
    http.verify();
  });
});
