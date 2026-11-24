import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { SILENT_ERRORS } from '../api/api.models';
import { AuthService } from '../auth/auth.service';
import { errorInterceptor, sessionInterceptor } from './http.interceptors';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let messages: MessageService;
  let auth: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        provideTranslateService(),
        MessageService,
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    messages = TestBed.inject(MessageService);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => backend.verify());

  function signIn(): void {
    auth.login('u', 'p').subscribe();
    backend.expectOne('/api/v1/auth/login').flush({ id: '1', fullName: 'U', workEmail: 'u@x', language: 'en', permissions: [], grants: [] });
  }

  it('clears the session and redirects to login when the server answers 401', () => {
    signIn();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    http.get('/api/v1/masters/branches').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/masters/branches').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.objectContaining({ queryParams: expect.objectContaining({ expired: 1 }) }));
  });

  it('expires an authenticated session on a silent 401 without showing a toast', () => {
    signIn();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const add = vi.spyOn(messages, 'add');
    http.get('/api/v1/me', { context: new HttpContext().set(SILENT_ERRORS, true) }).subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledOnce();
    expect(add).not.toHaveBeenCalled();
  });

  it('does not redirect when initial silent session restoration finds no session', () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    void auth.restoreSession();
    backend.expectOne('/api/v1/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('toasts server and network failures but not validation errors', () => {
    const add = vi.spyOn(messages, 'add');

    http.get('/a').subscribe({ error: () => undefined });
    backend.expectOne('/a').flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
    expect(add).toHaveBeenCalledTimes(1);

    http.get('/b').subscribe({ error: () => undefined });
    backend.expectOne('/b').error(new ProgressEvent('error'), { status: 0 });
    expect(add).toHaveBeenCalledTimes(2);

    http.post('/c', {}).subscribe({ error: () => undefined });
    backend.expectOne('/c').flush({ code: 'VALIDATION_FAILED' }, { status: 400, statusText: 'Bad Request' });
    expect(add).toHaveBeenCalledTimes(2);
  });

  it('does not toast when the caller handles the error itself', () => {
    const add = vi.spyOn(messages, 'add');
    http.post('/d', {}, { context: new HttpContext().set(SILENT_ERRORS, true) }).subscribe({ error: () => undefined });
    backend.expectOne('/d').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(add).not.toHaveBeenCalled();
  });
});
