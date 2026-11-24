import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Route, Router, RouterStateSnapshot, UrlSegment, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { authChildGuard, authGuard, globalPermissionGuard, permissionGuard, roleGuard } from './auth.guards';

@Component({ template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class EmptyPage {}

describe('route guards', () => {
  let auth: AuthService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    backend = TestBed.inject(HttpTestingController);
  });

  function run<T>(guard: () => T): T {
    return runInInjectionContext(TestBed.inject(EnvironmentInjector), guard);
  }

  function signInWith(permissions: string[], roleCodes: string[] = [], globalPermissions: string[] = []): void {
    auth.login('u', 'p').subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      id: '1', fullName: 'U', workEmail: 'u@x', language: 'en', permissions,
      grants: roleCodes.map((roleCode) => ({ roleCode, branchId: null })), globalPermissions,
    });
  }

  it('sends anonymous users to login with a return URL', () => {
    const segments = [new UrlSegment('administration', {}), new UrlSegment('branches', {})];
    const result = run(() => authGuard({} as Route, segments)) as UrlTree;
    expect(result.toString()).toBe('/login?returnUrl=%2Fadministration%2Fbranches');
  });

  it('preserves the query string in a signed-out deep link', async () => {
    const router = TestBed.inject(Router);
    router.resetConfig([
      { path: 'jobs/:id/finance', canMatch: [authGuard], component: EmptyPage },
      { path: 'login', component: EmptyPage },
    ]);
    await router.navigateByUrl('/jobs/123/finance?tab=fda');
    expect(router.parseUrl(router.url).queryParams['returnUrl']).toBe('/jobs/123/finance?tab=fda');
  });

  it('lets signed-in users through', () => {
    signInWith([]);
    expect(run(() => authGuard({} as Route, []))).toBe(true);
  });

  it('rechecks authentication on child navigation and preserves the requested URL', () => {
    const denied = run(() => authChildGuard({} as ActivatedRouteSnapshot, { url: '/dashboard' } as RouterStateSnapshot)) as UrlTree;
    expect(denied.toString()).toBe('/login?returnUrl=%2Fdashboard');
    signInWith([]);
    expect(run(() => authChildGuard({} as ActivatedRouteSnapshot, { url: '/dashboard' } as RouterStateSnapshot))).toBe(true);
  });

  it('routes users without the permission to access-denied', () => {
    signInWith(['MASTER.ORG.READ']);
    expect(run(() => permissionGuard('MASTER.ORG.READ')({} as Route, []))).toBe(true);
    const denied = run(() => permissionGuard('ADMIN.USER.READ')({} as Route, [])) as UrlTree;
    expect(denied.toString()).toBe('/access-denied');
  });

  it('denies a branch-scoped resource reader the company inventory route', () => {
    signInWith(['MASTER.RESOURCE.READ']);
    const denied = run(() => globalPermissionGuard('MASTER.RESOURCE.READ')({} as Route, [])) as UrlTree;
    expect(denied.toString()).toBe('/access-denied');
    signInWith(['MASTER.RESOURCE.READ'], ['SYSTEM_ADMIN'], ['MASTER.RESOURCE.READ']);
    expect(run(() => globalPermissionGuard('MASTER.RESOURCE.READ')({} as Route, []))).toBe(true);
  });

  it('restricts STS Admin pages by role as well as by read permission', () => {
    signInWith(['MASTER.COMMERCIAL.READ'], ['COMMERCIAL']);
    expect(run(() => roleGuard('STS_ADMIN')({} as Route, [])) instanceof UrlTree).toBe(true);
    signInWith(['MASTER.COMMERCIAL.READ'], ['STS_ADMIN']);
    expect(run(() => roleGuard('STS_ADMIN')({} as Route, []))).toBe(true);
  });
});
