import { FormControl } from '@angular/forms';
import { imoValidator, isValidImo } from './imo.validator';

describe('IMO validator', () => {
  it('accepts numbers whose 7th digit matches the weighted check sum', () => {
    expect(isValidImo('1000007')).toBe(true);
    expect(isValidImo('1000019')).toBe(true);
  });

  it('rejects wrong check digits and malformed values', () => {
    expect(isValidImo('1000008')).toBe(false);
    expect(isValidImo('123456')).toBe(false);
    expect(isValidImo('12345AB')).toBe(false);
  });

  it('leaves empty values to the required validator', () => {
    expect(imoValidator(new FormControl(''))).toBeNull();
    expect(imoValidator(new FormControl('1000008'))).toEqual({ imo: true });
  });
});
