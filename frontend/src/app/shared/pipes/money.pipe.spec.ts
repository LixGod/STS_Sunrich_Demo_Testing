import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { MoneyPipe } from './money.pipe';

describe('MoneyPipe', () => {
  let pipe: MoneyPipe;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideTranslateService(), MoneyPipe] });
    pipe = TestBed.inject(MoneyPipe);
  });

  it('rounds half-up exactly and groups thousands', () => {
    // 1.005 is 1.00499999... as a double; big.js rounds the decimal value.
    expect(pipe.transform('1.005')).toBe('1.01');
    expect(pipe.transform(1499.985, 'USD')).toBe('USD 1,499.99');
    expect(pipe.transform('10000.0000', 'INR')).toBe('INR 10,000.00');
  });

  it('shows nothing for empty values', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined, 'USD')).toBe('');
  });
});
