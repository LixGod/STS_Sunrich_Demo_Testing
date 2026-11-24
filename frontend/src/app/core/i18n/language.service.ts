import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';
import { provideTranslateMultiHttpLoader } from '@ngx-translate/http-loader';
import { firstValueFrom } from 'rxjs';
import { API_BASE_URL, SILENT_ERRORS } from '../api/api.models';
import { AuthService } from '../auth/auth.service';

export interface LanguageOption {
  code: string;
  label: string;
  dir: 'ltr' | 'rtl';
}

/** Languages with complete catalogues. Add an entry here plus `public/i18n/<code>/*.json` to offer another. */
export const SUPPORTED_LANGUAGES: readonly LanguageOption[] = [{ code: 'en', label: 'English', dir: 'ltr' }];

const FALLBACK = 'en';
const STORAGE_KEY = 'ship2ship.language';

/** Catalogues are split per area and merged when a language loads. */
export function provideI18n() {
  return provideTranslateService({
    loader: provideTranslateMultiHttpLoader({
      // Static files: bypass the API interceptors (they depend on TranslateService themselves).
      useHttpBackend: true,
      resources: [
        { prefix: '/i18n/', suffix: '/common.json' },
        { prefix: '/i18n/', suffix: '/administration.json' },
        { prefix: '/i18n/', suffix: '/commercial.json' },
        { prefix: '/i18n/', suffix: '/technical.json' },
        { prefix: '/i18n/', suffix: '/operations.json' },
        { prefix: '/i18n/', suffix: '/movements.json' },
        { prefix: '/i18n/', suffix: '/reports.json' },
        { prefix: '/i18n/', suffix: '/finance.json' },
      ],
    }),
    fallbackLang: FALLBACK,
  });
}

/**
 * Current UI language: the signed-in user's saved preference wins, then this browser's last choice,
 * then the browser language, then English. Also sets `<html lang dir>` for assistive technology.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly translate = inject(TranslateService);
  private readonly document = inject(DOCUMENT);
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);

  readonly current = signal(FALLBACK);
  readonly options: LanguageOption[] = [...SUPPORTED_LANGUAGES];

  constructor() {
    effect(() => {
      const preferred = this.auth.currentUser()?.language;
      if (preferred && preferred !== this.current() && this.isSupported(preferred)) {
        void this.use(preferred);
      }
    });
  }

  /** Loads the initial catalogue before the first screen renders (no flash of untranslated keys). */
  init(): Promise<void> {
    const stored = readStorage();
    const browser = this.document.defaultView?.navigator.language?.slice(0, 2);
    const initial = [stored, browser].find((code): code is string => !!code && this.isSupported(code)) ?? FALLBACK;
    return this.use(initial);
  }

  /** Switches language; when `remember` is set, the choice is also saved to the user's server profile. */
  async use(code: string, remember = false): Promise<void> {
    const language = SUPPORTED_LANGUAGES.find((l) => l.code === code) ?? SUPPORTED_LANGUAGES[0];
    await firstValueFrom(this.translate.use(language.code));
    this.current.set(language.code);
    this.document.documentElement.lang = language.code;
    this.document.documentElement.dir = language.dir;
    writeStorage(language.code);
    if (remember && this.auth.isAuthenticated()) {
      this.http
        .put(`${this.baseUrl}/me/preferences`, { language: language.code }, {
          context: new HttpContext().set(SILENT_ERRORS, true),
        })
        .subscribe({ error: () => undefined });
    }
  }

  private isSupported(code: string): boolean {
    return SUPPORTED_LANGUAGES.some((l) => l.code === code);
  }
}

// Storage can be unavailable (private mode, blocked site data); the app must work without it.
function readStorage(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeStorage(code: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, code);
  } catch {
    /* ignore */
  }
}
