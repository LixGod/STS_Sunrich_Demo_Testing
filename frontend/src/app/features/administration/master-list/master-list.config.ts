import { ValidatorFn } from '@angular/forms';
import { Permissions } from '../../../core/auth/permissions';
import { ColumnDef } from '../../../shared/components/data-table/table.models';
import { imoValidator } from '../../../shared/validators/imo.validator';

export type FieldType = 'text' | 'textarea' | 'integer' | 'decimal' | 'select' | 'date' | 'boolean';

/** Where a select field gets its options: a fixed list, or a cached master list from the lookup service. */
export type OptionSource = 'currencies' | 'units';

export interface MasterField {
  key: string;
  labelKey: string;
  type: FieldType;
  required?: boolean;
  /** Fixed choices; labels are translation keys. */
  options?: { value: string | number; labelKey: string }[];
  optionsFrom?: OptionSource;
  /** Allow an empty choice (e.g. "pending confirmation"). */
  clearable?: boolean;
  maxLength?: number;
  pattern?: RegExp;
  min?: number;
  max?: number;
  fractionDigits?: number;
  hintKey?: string;
  uppercase?: boolean;
  /** Cannot change after the record is created. */
  immutable?: boolean;
  span?: boolean;
  validators?: ValidatorFn[];
  defaultValue?: unknown;
}

export interface MasterListConfig {
  id: string;
  resource: string;
  titleKey: string;
  subtitleKey: string;
  newKey: string;
  writePermission: string;
  /** Audit entity type for the change history in the dialog. */
  entityType: string;
  /** Field shown as the record's identity in the dialog header. */
  identity: string;
  defaultSort: string;
  columns: ColumnDef<Record<string, unknown> & { id: string }>[];
  fields: MasterField[];
}

const CODE: MasterField = {
  key: 'code', labelKey: 'masters.code', type: 'text', required: true, maxLength: 20, uppercase: true, immutable: true,
  pattern: /^[A-Za-z0-9][A-Za-z0-9-]{0,19}$/, hintKey: 'masters.codeHintShort',
};
const NAME: MasterField = { key: 'name', labelKey: 'masters.name', type: 'text', required: true, maxLength: 100 };
const ACTIVE_COLUMN = { key: 'active', headerKey: 'masters.status', type: 'active' as const, sortField: 'active', width: '8rem' };

const option = (prefix: string) => (value: string) => ({ value, labelKey: `${prefix}.${value}` });

export const MASTER_LISTS: Record<string, MasterListConfig> = {
  operationTypes: {
    id: 'operationTypes', resource: '/masters/operation-types', titleKey: 'nav.operationTypes', subtitleKey: 'masters.operationType.subtitle',
    newKey: 'masters.operationType.new', writePermission: Permissions.masterCommercialWrite, entityType: 'OPERATION_TYPE',
    identity: 'code', defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '10rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      ACTIVE_COLUMN,
    ],
    fields: [CODE, NAME],
  },
  units: {
    id: 'units', resource: '/masters/units', titleKey: 'nav.units', subtitleKey: 'masters.unit.subtitle', newKey: 'masters.unit.new',
    writePermission: Permissions.masterCommercialWrite, entityType: 'UNIT_OF_MEASURE', identity: 'code', defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '8rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'dimension', headerKey: 'masters.unit.dimension', type: 'translated', value: (r) => `enums.dimension.${r['dimension']}` },
      ACTIVE_COLUMN,
    ],
    fields: [CODE, NAME, {
      key: 'dimension', labelKey: 'masters.unit.dimension', type: 'select', required: true,
      options: ['MASS', 'VOLUME', 'OTHER'].map(option('enums.dimension')),
    }],
  },
  cargoGrades: {
    id: 'cargoGrades', resource: '/masters/cargo-grades', titleKey: 'nav.cargoGrades', subtitleKey: 'masters.cargoGrade.subtitle',
    newKey: 'masters.cargoGrade.new', writePermission: Permissions.masterCommercialWrite, entityType: 'CARGO_GRADE', identity: 'code',
    defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '9rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'category', headerKey: 'masters.cargoGrade.category', type: 'translated', value: (r) => `enums.cargoCategory.${r['category']}` },
      ACTIVE_COLUMN,
    ],
    fields: [CODE, NAME, {
      key: 'category', labelKey: 'masters.cargoGrade.category', type: 'select', required: true,
      options: ['PETROLEUM', 'LPG', 'LNG', 'CHEMICAL', 'OTHER'].map(option('enums.cargoCategory')),
    }, { key: 'defaultUnitId', labelKey: 'masters.cargoGrade.defaultUnit', type: 'select', optionsFrom: 'units', clearable: true }],
  },
  currencies: {
    id: 'currencies', resource: '/masters/currencies', titleKey: 'nav.currencies', subtitleKey: 'masters.currency.subtitle',
    newKey: 'masters.currency.new', writePermission: Permissions.masterFinanceWrite, entityType: 'CURRENCY', identity: 'code',
    defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '7rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'minorUnits', headerKey: 'masters.currency.minorUnits', width: '9rem' },
      ACTIVE_COLUMN,
    ],
    fields: [
      { ...CODE, pattern: /^[A-Za-z]{3}$/, maxLength: 3, hintKey: 'masters.currency.codeHint' },
      NAME,
      { key: 'minorUnits', labelKey: 'masters.currency.minorUnits', type: 'integer', required: true, min: 0, max: 4, defaultValue: 2 },
    ],
  },
  documentTypes: {
    id: 'documentTypes', resource: '/masters/document-types', titleKey: 'nav.documentTypes', subtitleKey: 'masters.documentType.subtitle',
    newKey: 'masters.documentType.new', writePermission: Permissions.masterTechnicalWrite, entityType: 'DOCUMENT_TYPE', identity: 'code',
    defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '10rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'appliesTo', headerKey: 'masters.documentType.appliesTo', type: 'translated', value: (r) => `enums.documentOwner.${r['appliesTo']}` },
      { key: 'required', headerKey: 'masters.documentType.required', type: 'translated', value: (r) => (r['required'] ? 'common.yes' : 'common.no'), width: '9rem' },
      ACTIVE_COLUMN,
    ],
    fields: [CODE, NAME, {
      key: 'appliesTo', labelKey: 'masters.documentType.appliesTo', type: 'select', required: true,
      options: ['VESSEL', 'JOB', 'EQUIPMENT', 'PERSONNEL'].map(option('enums.documentOwner')),
    }, {
      key: 'required', labelKey: 'masters.documentType.required', type: 'boolean', defaultValue: false, hintKey: 'masters.documentType.requiredHint',
    }],
  },
  costHeads: {
    id: 'costHeads', resource: '/masters/cost-heads', titleKey: 'nav.costHeads', subtitleKey: 'masters.costHead.subtitle',
    newKey: 'masters.costHead.new', writePermission: Permissions.masterFinanceWrite, entityType: 'COST_HEAD', identity: 'code',
    defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '10rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'kind', headerKey: 'masters.costHead.kind', type: 'translated', value: (r) => `enums.costKind.${r['kind']}` },
      ACTIVE_COLUMN,
    ],
    fields: [CODE, NAME, {
      key: 'kind', labelKey: 'masters.costHead.kind', type: 'select', required: true,
      options: ['EXPENSE', 'REVENUE', 'BOTH'].map(option('enums.costKind')),
    }],
  },
  vessels: {
    id: 'vessels', resource: '/masters/vessels', titleKey: 'nav.vessels', subtitleKey: 'masters.vessel.subtitle',
    newKey: 'masters.vessel.new', writePermission: Permissions.masterCommercialWrite, entityType: 'VESSEL', identity: 'name',
    defaultSort: 'name,asc',
    columns: [
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'imo', headerKey: 'masters.vessel.imo', sortField: 'imo', width: '8rem' },
      { key: 'vesselType', headerKey: 'masters.vessel.type', type: 'translated', value: (r) => `enums.vesselType.${r['vesselType']}` },
      { key: 'loaM', headerKey: 'masters.vessel.loa', width: '7rem' },
      { key: 'particularsStatus', headerKey: 'masters.vessel.particulars', type: 'status', width: '9rem' },
      ACTIVE_COLUMN,
    ],
    fields: [
      { key: 'imo', labelKey: 'masters.vessel.imo', type: 'text', required: true, maxLength: 7, immutable: true,
        validators: [imoValidator], hintKey: 'masters.vessel.imoHint' },
      { key: 'name', labelKey: 'masters.name', type: 'text', required: true, maxLength: 150, uppercase: true },
      { key: 'vesselType', labelKey: 'masters.vessel.type', type: 'select', required: true,
        options: ['CRUDE_TANKER', 'PRODUCT_TANKER', 'CHEMICAL_TANKER', 'LPG_CARRIER', 'LNG_CARRIER', 'BULK_CARRIER', 'OTHER']
          .map(option('enums.vesselType')) },
      { key: 'flagState', labelKey: 'masters.vessel.flag', type: 'text', maxLength: 2, uppercase: true, pattern: /^[A-Za-z]{2}$/,
        hintKey: 'masters.countryHint' },
      { key: 'loaM', labelKey: 'masters.vessel.loa', type: 'decimal', min: 1, max: 500, fractionDigits: 2 },
      { key: 'beamM', labelKey: 'masters.vessel.beam', type: 'decimal', min: 1, max: 100, fractionDigits: 2 },
      { key: 'dwtT', labelKey: 'masters.vessel.dwt', type: 'decimal', min: 0, fractionDigits: 1 },
      { key: 'formerNames', labelKey: 'masters.vessel.formerNames', type: 'text', maxLength: 500, span: true },
    ],
  },
  tariffs: {
    id: 'tariffs', resource: '/masters/tariffs', titleKey: 'nav.tariffs', subtitleKey: 'masters.tariff.subtitle',
    newKey: 'masters.tariff.new', writePermission: Permissions.masterFinanceWrite, entityType: 'TARIFF', identity: 'code',
    defaultSort: 'code,asc',
    columns: [
      { key: 'code', headerKey: 'masters.code', sortField: 'code', width: '9rem' },
      { key: 'name', headerKey: 'masters.name', sortField: 'name' },
      { key: 'baseHours', headerKey: 'masters.tariff.baseHours', value: (r) => `${r['baseHours']} h`, width: '7rem' },
      { key: 'effectiveFrom', headerKey: 'masters.effectiveFrom', sortField: 'effectiveFrom', width: '9rem' },
      { key: 'termsPending', headerKey: 'masters.tariff.terms', type: 'status',
        value: (r) => (r['termsPending'] ? 'PENDING_CONFIRMATION' : 'CONFIRMED'), width: '10rem' },
      ACTIVE_COLUMN,
    ],
    fields: [
      { ...CODE, maxLength: 20 },
      { ...NAME, maxLength: 150 },
      { key: 'currency', labelKey: 'masters.tariff.currency', type: 'select', required: true, optionsFrom: 'currencies', defaultValue: 'USD' },
      { key: 'baseHours', labelKey: 'masters.tariff.baseHours', type: 'select', required: true, defaultValue: 48,
        options: [24, 48, 72].map((h) => ({ value: h, labelKey: `masters.tariff.hours${h}` })), hintKey: 'masters.tariff.baseHoursHint' },
      { key: 'baseCharge', labelKey: 'masters.tariff.baseCharge', type: 'decimal', required: true, min: 0, fractionDigits: 4 },
      { key: 'extraHourRate', labelKey: 'masters.tariff.extraHourRate', type: 'decimal', required: true, min: 0, fractionDigits: 4 },
      { key: 'roundingRule', labelKey: 'masters.tariff.rounding', type: 'select', clearable: true,
        options: ['NONE', 'ROUND_UP_TO_INCREMENT'].map(option('enums.roundingRule')), hintKey: 'masters.tariff.pendingHint' },
      { key: 'billingIncrementMinutes', labelKey: 'masters.tariff.increment', type: 'integer', min: 1, hintKey: 'masters.tariff.pendingHint' },
      { key: 'graceMinutes', labelKey: 'masters.tariff.grace', type: 'integer', min: 0, hintKey: 'masters.tariff.pendingHint' },
      { key: 'effectiveFrom', labelKey: 'masters.effectiveFrom', type: 'date', required: true },
      { key: 'effectiveTo', labelKey: 'masters.effectiveTo', type: 'date' },
      { key: 'notes', labelKey: 'masters.notes', type: 'textarea', maxLength: 500, span: true },
    ],
  },
};
