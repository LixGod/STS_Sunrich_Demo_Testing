import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';
import { SILENT_ERRORS, toProblem } from '../api/api.models';
import { AuthService } from '../auth/auth.service';

/** Once signed in, any API 401 means the server session ended, even on requests with quiet error handling. */
export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        auth.isAuthenticated()
      ) {
        auth.clearSession();
        void router.navigate(['/login'], { queryParams: { returnUrl: router.url, expired: 1 } });
      }
      return throwError(() => error);
    }),
  );
};

/**
 * Shows a toast for failures the screen cannot fix inline: no connection, server errors, refused access.
 * Validation and conflict responses (400/409/422/428) are left to the calling form.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const injector = inject(Injector);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && !request.context.get(SILENT_ERRORS)) {
        const key = toastKey(error.status);
        if (key) {
          // Resolved lazily: TranslateService itself uses HttpClient, so injecting it eagerly would be circular.
          const messages = injector.get(MessageService);
          const translate = injector.get(TranslateService);
          const problem = toProblem(error);
          messages.add({
            severity: error.status === 403 ? 'warn' : 'error',
            summary: translate.instant(key),
            detail: problem.code === 'NETWORK_ERROR' ? translate.instant('errors.networkDetail') : undefined,
            life: 6000,
          });
        }
      }
      return throwError(() => error);
    }),
  );
};

function toastKey(status: number): string | null {
  if (status === 0) return 'errors.network';
  if (status === 403) return 'errors.accessDenied';
  if (status === 404) return 'errors.notFound';
  if (status === 503) return 'errors.unavailable';
  if (status >= 500) return 'errors.server';
  return null;
}
