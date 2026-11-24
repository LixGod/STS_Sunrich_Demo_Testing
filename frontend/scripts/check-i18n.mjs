// Checks that every literal translation key used in templates and code exists in each language's catalogues,
// and that every value produced for the dynamic key families (status., audit.action., ...) is translated.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const here = (p) => new URL(p, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const appDir = here('../src/app');
const i18nDir = here('../public/i18n');

const DYNAMIC = {
  'status.': ['ACTIVE', 'INACTIVE', 'SUSPENDED', 'DEACTIVATED', 'DRAFT', 'COSTING', 'QUOTATION_APPROVAL', 'QUOTED', 'ACCEPTED', 'LOST', 'CANCELLED', 'UNVERIFIED', 'VERIFIED', 'PENDING_CONFIRMATION', 'CONFIRMED'],
  'audit.action.': ['CREATE', 'UPDATE', 'STATUS_CHANGE', 'ROLE_SCOPES_CHANGE', 'LOGIN', 'LOGIN_FAILED', 'LOGIN_REJECTED', 'LOGOUT', 'ACCESS_DENIED', 'BOOTSTRAP'],
  'audit.entity.': ['USER', 'LEGAL_ENTITY', 'BRANCH', 'CLIENT', 'CLIENT_CONTACT', 'VESSEL', 'UNIT_OF_MEASURE', 'CARGO_GRADE', 'CURRENCY', 'COST_HEAD', 'TAX_CODE', 'FX_RATE', 'TARIFF', 'ENQUIRY'],
  'enquiry.transition.': ['COSTING', 'LOST', 'CANCELLED'],
  'enquiry.transitionHint.': ['COSTING', 'LOST', 'CANCELLED'],
  'enquiry.section.': ['general', 'client', 'vessels', 'cargo', 'operation'],
  'enums.vesselType.': ['CRUDE_TANKER', 'PRODUCT_TANKER', 'CHEMICAL_TANKER', 'LPG_CARRIER', 'LNG_CARRIER', 'BULK_CARRIER', 'OTHER'],
  'enums.operationType.': ['PETROLEUM', 'LPG', 'LNG', 'CHEMICAL', 'OTHER'],
  'enums.clientRole.': ['OWNER', 'CHARTERER', 'TRADER', 'AGENT', 'OTHER'],
  'enums.dimension.': ['MASS', 'VOLUME', 'OTHER'],
  'enums.cargoCategory.': ['PETROLEUM', 'LPG', 'LNG', 'CHEMICAL', 'OTHER'],
  'enums.costKind.': ['EXPENSE', 'REVENUE', 'BOTH'],
  'enums.roundingRule.': ['NONE', 'ROUND_UP_TO_INCREMENT'],
  'enquiry.': ['mother', 'daughter'],
  'roles.': ['SYSTEM_ADMIN', 'MANAGEMENT_APPROVER', 'COMMERCIAL', 'OPERATIONS', 'TECHNICAL', 'FINANCE', 'AUDITOR'],
  'permissions.': ['ADMIN.USER.READ', 'ADMIN.USER.WRITE', 'ADMIN.ROLE.READ', 'MASTER.ORG.READ', 'MASTER.ORG.WRITE', 'AUDIT.READ'],
  'comingSoon.': ['technical', 'operations', 'finance', 'reports'],
  'users.statusChange.': ['ACTIVE', 'SUSPENDED', 'DEACTIVATED'],
  'validation.': ['required', 'email', 'pattern', 'maxlength', 'minlength', 'min', 'max', 'server', 'imo'],
  'errors.codes.': ['STALE_VERSION', 'HAS_ACTIVE_CHILDREN', 'PARENT_INACTIVE', 'DUPLICATE_CODE', 'DUPLICATE_EMAIL', 'DUPLICATE_SUBJECT', 'SELF_MODIFICATION_FORBIDDEN', 'CODE_IMMUTABLE', 'ACCESS_DENIED', 'VALIDATION_FAILED', 'INVALID_GRANTS', 'REASON_REQUIRED', 'NOT_FOUND', 'NETWORK_ERROR', 'ENQUIRY_LOCKED', 'INVALID_TRANSITION', 'DUPLICATE_IMO', 'DUPLICATE_RATE', 'TARIFF_PERIOD_OVERLAP', 'SCOPE_IMMUTABLE'],
};
const NAMESPACES = ['nav', 'common', 'table', 'filters', 'status', 'validation', 'errors', 'auth', 'dashboard', 'comingSoon', 'audit', 'masters', 'users', 'roles', 'permissions', 'enquiry', 'enums', 'movements', 'jobFinance', 'job'];

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : /\.(ts|html)$/.test(name) && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

function lookup(catalogue, key) {
  return key.split('.').reduce((node, part) => (node && typeof node === 'object' ? node[part] : undefined), catalogue);
}

const keyPattern = new RegExp(`['"]((?:${NAMESPACES.join('|')})\\.[A-Za-z0-9_.]+)['"]`, 'g');
const used = new Set();
for (const file of walk(appDir)) {
  for (const match of readFileSync(file, 'utf8').matchAll(keyPattern)) {
    // `permissions.<camelCase>` in templates is the Permissions constant, not a translation key.
    if (!match[1].endsWith('.') && !/^permissions\.[a-z]/.test(match[1])) used.add(match[1]);
  }
}
for (const [prefix, values] of Object.entries(DYNAMIC)) values.forEach((v) => used.add(prefix + v));

let failures = 0;
for (const language of readdirSync(i18nDir)) {
  const catalogue = {};
  for (const file of readdirSync(join(i18nDir, language))) {
    Object.assign(catalogue, JSON.parse(readFileSync(join(i18nDir, language, file), 'utf8')));
  }
  // A key that resolves to a section (e.g. a prefix passed to a helper such as option("enums.vesselType")) is fine.
  const missing = [...used].filter((key) => { const v = lookup(catalogue, key); return typeof v !== "string" && (v === undefined || typeof v !== "object"); }).sort();
  if (missing.length) {
    failures += missing.length;
    console.error(`[${language}] ${missing.length} missing key(s):\n  ${missing.join('\n  ')}`);
  } else {
    console.log(`[${language}] all ${used.size} keys present.`);
  }
}
process.exit(failures ? 1 : 0);
