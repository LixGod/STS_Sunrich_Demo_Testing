import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';
import { LanguageService } from './language.service';

describe('LanguageService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService({ fallbackLang: 'en' })],
    });
  });

  it('applies a saved supported language on start-up (the HRMS bug always forced English)', async () => {
    localStorage.setItem('ship2ship.language', 'en');
    const service = TestBed.inject(LanguageService);
    await service.init();
    expect(service.current()).toBe('en');
    expect(TestBed.inject(TranslateService).getCurrentLang()).toBe('en');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('falls back to English for an unsupported saved language', async () => {
    localStorage.setItem('ship2ship.language', 'xx');
    const service = TestBed.inject(LanguageService);
    await service.init();
    expect(service.current()).toBe('en');
  });

  it('saves the choice locally and does not call the server when nobody is signed in', async () => {
    const service = TestBed.inject(LanguageService);
    await service.use('en', true);
    expect(localStorage.getItem('ship2ship.language')).toBe('en');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/me/preferences');
  });
});
