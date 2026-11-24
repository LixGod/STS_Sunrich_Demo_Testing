import { HttpContextToken, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { InjectionToken } from '@angular/core';

/** Same-origin API root; Nginx (and the dev-server proxy) forward `/api` to Spring Boot. */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', { factory: () => '/api/v1' });

/** Set on a request when the caller shows its own error message (no global toast). */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

/** Server list envelope (see docs/architecture/api-conventions.md). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export type QueryValue = string | number | boolean | null | undefined;

export interface PageQuery {
  page: number;
  size: number;
  /** `field,asc` or `field,desc`; only server-whitelisted fields are accepted. */
  sort?: string;
  q?: string;
  /** Extra filters, e.g. `{ active: true, branchId: '…' }`. Empty values are omitted. */
  filters?: Record<string, QueryValue>;
}

export interface FieldProblem {
  field: string;
  code: string;
  message?: string | null;
}

/** RFC 9457 Problem Details as produced by the backend, plus a stable `code`. */
export interface Problem {
  status: number;
  code: string;
  detail?: string;
  errors?: FieldProblem[];
}

export function toProblem(error: unknown): Problem {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return { status: 0, code: 'NETWORK_ERROR' };
    }
    const body = error.error as Partial<Problem> | null;
    if (body && typeof body === 'object' && typeof body.code === 'string') {
      return { status: error.status, code: body.code, detail: body.detail, errors: body.errors };
    }
    return { status: error.status, code: `HTTP_${error.status}` };
  }
  return { status: -1, code: 'UNKNOWN_ERROR' };
}

const KNOWN_CODES = new Set([
  'STALE_VERSION', 'VERSION_REQUIRED', 'INVALID_VERSION', 'HAS_ACTIVE_CHILDREN', 'PARENT_INACTIVE', 'DUPLICATE_CODE', 'DUPLICATE_EMAIL', 'DUPLICATE_SUBJECT',
  'AUTH_PROVIDER_UNAVAILABLE', 'ACCOUNT_NOT_ACTIVE', 'OFFLINE_EVENT_CONFLICT', 'JOB_BLOCKED', 'INVALID_SORT', 'INVALID_PAGE', 'INVALID_TIME_ZONE',
  'TEMPLATE_PENDING', 'CHECKLIST_INCOMPLETE',
  'SELF_MODIFICATION_FORBIDDEN', 'CODE_IMMUTABLE', 'ACCESS_DENIED', 'VALIDATION_FAILED', 'INVALID_GRANTS',
  'REASON_REQUIRED', 'NOT_FOUND', 'NETWORK_ERROR', 'CONSTRAINT_VIOLATION', 'ENQUIRY_LOCKED', 'INVALID_TRANSITION', 'DUPLICATE_IMO',
  'DUPLICATE_RATE', 'TARIFF_PERIOD_OVERLAP', 'SCOPE_IMMUTABLE', 'ESTIMATE_LOCKED', 'ESTIMATE_HAS_NO_REVENUE',
  'QUOTATION_VERSION_OPEN', 'SELF_APPROVAL_FORBIDDEN', 'ALREADY_ACCEPTED', 'TARIFF_REQUIRED', 'FILE_TOO_LARGE', 'FILE_EMPTY',
  'FILE_TYPE_NOT_ALLOWED', 'PARTICULARS_VERSION_OPEN', 'PARTICULARS_EMPTY', 'SOURCE_REQUIRED', 'SELF_VERIFICATION_FORBIDDEN',
  'JPO_INCOMPLETE', 'JPO_REVISION_OPEN', 'ALREADY_RECORDED', 'ANNEX_DRAFT_OPEN', 'ANNEX_WORDING_MISSING', 'SECOND_PERSON_REQUIRED',
  'MOORING_INCOMPLETE', 'MOORING_REVISION_OPEN',
  'LIMITS_REVISION_OPEN', 'LIMITS_EMPTY', 'PARAMETER_INACTIVE', 'PARAMETER_IN_USE',
  'SOF_REVISION_OPEN', 'SOF_EMPTY', 'CARGO_TOTAL_FALLING', 'READING_OUT_OF_ORDER', 'CARGO_COMPLETED',
  'REPORT_REVISION_OPEN', 'REPORT_INCOMPLETE',
  'INVOICE_INCOMPLETE', 'CREDIT_EXCEEDS_OUTSTANDING', 'ALLOCATION_EXCEEDS_RECEIPT', 'ALLOCATION_EXCEEDS_OUTSTANDING', 'ALLOCATION_NOT_ALLOWED',
  'CURRENCY_MISMATCH', 'TARIFF_IN_USE',
  'DUPLICATE_REFERENCE', 'FDA_REVISION_OPEN', 'FDA_SOURCES_CHANGED', 'JOB_LOCKED', 'EXTRA_CHARGE_RECONCILED',
  'DUPLICATE_ASSET_ID', 'INVALID_CERTIFICATE_DATES', 'RESOURCE_INELIGIBLE', 'CERTIFICATE_POLICY_PENDING',
  'CERTIFICATE_REQUIRED', 'ALLOCATION_COLLISION', 'ALLOCATION_COLLISION_GONE', 'UNIT_IN_USE',
  'MOVEMENT_DESIGNATED', 'CLOCK_CHANGE_PENDING', 'TARIFF_TERMS_PENDING', 'RECOVERY_DISCREPANCY',
  'FINAL_INVOICE_ISSUED', 'TARIFF_MISMATCH',
]);

/** Translation key for a problem shown as a page-level message (`errors.codes.<CODE>`). */
export function problemKey(problem: Problem): string {
  return KNOWN_CODES.has(problem.code) ? `errors.codes.${problem.code}` : 'errors.saveFailed';
}

export function toHttpParams(values: Record<string, QueryValue>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

export function pageParams(query: PageQuery): HttpParams {
  return toHttpParams({
    page: query.page,
    size: query.size,
    sort: query.sort,
    q: query.q?.trim(),
    ...query.filters,
  });
}

/** Value for an `If-Match` header carrying the record version the user edited. */
export function ifMatch(version: number | null | undefined): string {
  return `"${version ?? 0}"`;
}
