/** 'translated': the cell value is a translation key (e.g. an enum label). */
export type ColumnType = 'text' | 'translated' | 'datetime' | 'active' | 'status';

export type CellValue = string | number | boolean | null | undefined;

export interface ColumnDef<T> {
  /** Unique column id; also the row property shown when `value` is not given. */
  key: string;
  headerKey: string;
  /** Server sort field name; the column is sortable only when set. */
  sortField?: string;
  type?: ColumnType;
  value?: (row: T, index: number) => CellValue;
  /** Makes the cell a link (typically the record's main column). */
  link?: (row: T) => string[];
  width?: string;
}

export interface RowAction<T> {
  icon: string;
  labelKey: string;
  severity?: 'secondary' | 'danger' | 'success' | 'info' | 'warn' | 'contrast';
  /** Hidden unless the user holds this permission (display only; the server still checks). */
  permission?: string;
  visible?: (row: T) => boolean;
  run: (row: T) => void;
}
