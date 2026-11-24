import { inject } from '@angular/core';
import { CanActivateChildFn, CanMatchFn, Router, UrlSegment } from '@angular/router';
import { AuthService } from '../auth/auth.service';

/** Lets signed-in users through; otherwise sends them to the login page and back afterwards. */
export const authGuard: CanMatchFn = (_route, segments: UrlSegment[]) => {
  const router = inject(Router);
  if (inject(AuthService).isAuthenticated()) {
    return true;
  }
  const returnUrl = router.currentNavigation()?.initialUrl.toString() ?? '/' + segments.map((s) => s.path).join('/');
  return router.createUrlTree(['/login'], {
    queryParams: returnUrl === '/' ? {} : { returnUrl },
  });
};

/** Rechecks authentication on child navigations after the shell route has already been matched. */
export const authChildGuard: CanActivateChildFn = (_route, state) => {
  if (inject(AuthService).isAuthenticated()) return true;
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Keeps signed-in users away from the login page. */
export const anonymousGuard: CanMatchFn = () =>
  inject(AuthService).isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;

/**
 * Route is shown only if the user holds at least one of the permissions. This is a navigation convenience;
 * the server makes the real decision on every request.
 */
export function permissionGuard(...permissions: string[]): CanMatchFn {
  return () =>
    inject(AuthService).hasAnyPermission(permissions) ? true : inject(Router).createUrlTree(['/access-denied']);
}

export function globalPermissionGuard(permission: string): CanMatchFn {
  return () =>
    inject(AuthService).hasGlobalPermission(permission) ? true : inject(Router).createUrlTree(['/access-denied']);
}

/** Restricts setup screens to the STS Admin role, even when another role can read the same lookup data. */
export function roleGuard(roleCode: string): CanMatchFn {
  return () => inject(AuthService).currentUser()?.grants.some((grant) => grant.roleCode === roleCode)
    ? true
    : inject(Router).createUrlTree(['/access-denied']);
}
