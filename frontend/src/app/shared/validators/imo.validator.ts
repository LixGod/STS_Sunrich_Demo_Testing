import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** IMO ship identification number: 7 digits, the last equal to sum(d1×7 … d6×2) mod 10. Same rule as the server. */
export function isValidImo(value: string): boolean {
  if (!/^\d{7}$/.test(value)) return false;
  const sum = [...value.slice(0, 6)].reduce((total, digit, i) => total + Number(digit) * (7 - i), 0);
  return sum % 10 === Number(value[6]);
}

export const imoValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = String(control.value ?? '').trim();
  return value === '' || isValidImo(value) ? null : { imo: true };
};
