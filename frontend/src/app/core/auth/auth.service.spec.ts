import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService logout', () => {
  it('keeps the session visible when the server cannot confirm logout', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    auth.login('u', 'p').subscribe();
    http.expectOne('/api/v1/auth/login').flush({ id: '1', fullName: 'U', workEmail: 'u@x', language: 'en', permissions: [], grants: [] });

    let failed = false;
    auth.logout().subscribe({ error: () => { failed = true; } });
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 503, statusText: 'Unavailable' });
    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('accepts an already expired server session as signed out', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    auth.login('u', 'p').subscribe();
    http.expectOne('/api/v1/auth/login').flush({ id: '1', fullName: 'U', workEmail: 'u@x', language: 'en', permissions: [], grants: [] });

    let completed = false;
    auth.logout().subscribe({ next: () => { completed = true; } });
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });
});
