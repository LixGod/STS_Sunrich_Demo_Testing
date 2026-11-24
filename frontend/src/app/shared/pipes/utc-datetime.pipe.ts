import { Pipe, PipeTransform, inject } from '@angular/core';
import { LanguageService } from '../../core/i18n/language.service';

const formatters = new Map<string, Intl.DateTimeFormat>();

/**
 * Formats a UTC instant from the API (ISO-8601) in a given IANA timezone — e.g. the branch's — or the
 * browser's own zone, always showing the zone name so readers know which clock a time refers to.
 */
@Pipe({ name: 'utcDateTime' })
export class UtcDateTimePipe implements PipeTransform {
  private readonly language = inject(LanguageService);

  transform(value: string | null | undefined, timeZone?: string | null): string {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    const locale = this.language.current();
    const key = `${locale}|${timeZone ?? ''}`;
    let formatter = formatters.get(key);
    if (!formatter) {
      formatter = new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: timeZone ?? undefined,
      });
      formatters.set(key, formatter);
    }
    const zone = timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
    return `${formatter.format(date)} (${zone})`;
  }
}
