import { Pipe, PipeTransform, inject } from '@angular/core';
import Big from 'big.js';
import { LanguageService } from '../../core/i18n/language.service';

/**
 * Money for display: rounded half-up to `digits` decimals with big.js (exact), then grouped for the user's language.
 * Stored and calculated values keep their full precision; this only affects what is shown.
 */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  private readonly language = inject(LanguageService);

  transform(value: number | string | Big | null | undefined, currency?: string | null, digits = 2): string {
    if (value === null || value === undefined || value === '') return '';
    const rounded = new Big(value).round(digits, Big.roundHalfUp).toFixed(digits);
    const formatted = new Intl.NumberFormat(this.language.current(), { minimumFractionDigits: digits, maximumFractionDigits: digits })
      .format(Number(rounded));
    return currency ? `${currency} ${formatted}` : formatted;
  }
}
