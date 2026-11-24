import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { UtcDateTimePipe } from './utc-datetime.pipe';

describe('UtcDateTimePipe', () => {
  let pipe: UtcDateTimePipe;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService(), UtcDateTimePipe],
    });
    pipe = TestBed.inject(UtcDateTimePipe);
  });

  it('shows a UTC instant in the requested zone and names the zone', () => {
    const text = pipe.transform('2026-10-16T05:30:00Z', 'Asia/Kolkata');
    expect(text).toContain('11:00');
    expect(text).toContain('(Asia/Kolkata)');
  });

  it('returns an empty string for missing or invalid values', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform('not a date')).toBe('');
  });
});
