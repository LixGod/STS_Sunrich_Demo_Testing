import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_BASE_URL } from '../../core/api/api.models';
import { ResourceClient } from '../../core/api/resource.client';

export interface Branch {
  id: string;
  code: string;
  name: string;
  timezone: string;
  address: string | null;
  active: boolean;
  updatedAt: string | null;
  version: number | null;
}

export type BranchRequest = Pick<Branch, 'code' | 'name' | 'timezone' | 'address'>;

export interface Option {
  label: string;
  value: string;
}

@Injectable({ providedIn: 'root' })
export class BranchService extends ResourceClient<Branch, BranchRequest> {
  constructor() {
    super(inject(HttpClient), `${inject(API_BASE_URL)}/masters/branches`);
  }

  options(): Observable<Option[]> {
    return this.list({ page: 0, size: 100, sort: 'code,asc', filters: { active: true } }).pipe(
      map((page) => page.items.map((b) => ({ label: `${b.code} — ${b.name}`, value: b.id }))),
    );
  }
}

/**
 * Some browsers (Chromium's ICU data) still list legacy IANA names; map them to the current names so values
 * saved on one browser match the options on another. Both forms are accepted by the server.
 */
const CURRENT_ZONE_NAMES: Record<string, string> = {
  'Asia/Calcutta': 'Asia/Kolkata',
  'Asia/Saigon': 'Asia/Ho_Chi_Minh',
  'Asia/Katmandu': 'Asia/Kathmandu',
  'Asia/Rangoon': 'Asia/Yangon',
  'Europe/Kiev': 'Europe/Kyiv',
  'America/Godthab': 'America/Nuuk',
  'Atlantic/Faeroe': 'Atlantic/Faroe',
};

/** IANA region IDs supported by this browser (e.g. "Asia/Kolkata"); the server validates the value again. */
export function timeZoneOptions(): string[] {
  try {
    const zones = Intl.supportedValuesOf('timeZone')
      .filter((zone) => zone.includes('/'))
      .map((zone) => CURRENT_ZONE_NAMES[zone] ?? zone);
    return [...new Set(zones)].sort();
  } catch {
    // Very old browsers lack Intl.supportedValuesOf; offer the zones of the configured branches' regions.
    return ['Asia/Dubai', 'Asia/Kolkata', 'Asia/Singapore', 'Europe/London'];
  }
}
