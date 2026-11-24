import { provideHttpClient, withFetch, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { ApplicationConfig, inject, isDevMode, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { ConfirmationService, MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { LanguageService, provideI18n } from './core/i18n/language.service';
import { errorInterceptor, sessionInterceptor } from './core/interceptors/http.interceptors';
import { Ship2ShipTheme } from './theme';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideServiceWorker('ngsw-worker.js', { enabled: !isDevMode(), registrationStrategy: 'registerWhenStable:30000' }),
    // Lazy feature routes are loaded on demand only (no preloading) to save bandwidth and memory.
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' })),
    provideHttpClient(
      withFetch(),
      // Matches Spring Security's SPA CSRF cookie/header names.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([sessionInterceptor, errorInterceptor]),
    ),
    providePrimeNG({
      theme: { preset: Ship2ShipTheme, options: { darkModeSelector: '.s2s-dark' } },
      ripple: false,
    }),
    MessageService,
    ConfirmationService,
    provideI18n(),
    provideAppInitializer(async () => {
      const language = inject(LanguageService);
      const auth = inject(AuthService);
      await language.init();
      await auth.restoreSession();
    }),
  ],
};
