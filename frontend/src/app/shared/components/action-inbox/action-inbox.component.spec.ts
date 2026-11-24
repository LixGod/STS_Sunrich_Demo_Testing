import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../../core/auth/auth.service';
import { ActionInboxComponent } from './action-inbox.component';

describe('ActionInboxComponent', () => {
  it('cancels an older page request when the filter changes', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: { currentUser: () => ({ id: 'user-1' }), hasPermission: () => false } },
    ] });
    TestBed.overrideComponent(ActionInboxComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(ActionInboxComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const old = http.expectOne((request) => request.url === '/api/v1/alerts');
    fixture.componentInstance['state'] = 'OPEN';
    fixture.componentInstance['filter']();
    expect(old.cancelled).toBe(true);
    http.expectOne('/api/v1/alerts?page=0&size=25&state=OPEN').flush({ items: [], page: 0, size: 25, totalElements: 0 });
    expect(fixture.componentInstance['data']()?.totalElements).toBe(0);
    http.verify();
  });
});
