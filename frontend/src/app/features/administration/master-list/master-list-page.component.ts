import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked, viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { DatePicker } from 'primeng/datepicker';
import { Dialog } from 'primeng/dialog';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { Observable, of } from 'rxjs';
import { API_BASE_URL, PageQuery, problemKey, toProblem } from '../../../core/api/api.models';
import { ResourceClient, Versioned } from '../../../core/api/resource.client';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { ActiveFilterComponent } from '../../../shared/components/active-filter/active-filter.component';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { RowAction } from '../../../shared/components/data-table/table.models';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { CanDirective } from '../../../shared/directives/can.directive';
import { MasterField, MasterListConfig, OptionSource } from './master-list.config';

type MasterRow = Versioned & Record<string, unknown> & { active?: boolean };

/**
 * One configurable screen for flat master lists (units, cargo grades, currencies, cost heads, vessels,
 * tariffs): server-paged list, add/edit dialog, activate/deactivate with a reason, change history.
 * Each list's fields and columns live in `master-list.config.ts`.
 */
@Component({
  selector: 's2s-master-list-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Checkbox, DatePicker, Dialog, InputNumber, InputText, Message, Select, Textarea,
    DataTableComponent, ActiveFilterComponent, AuditTrailComponent, FormFieldComponent, PageHeaderComponent,
    StatusTagComponent, CanDirective,
  ],
  templateUrl: './master-list-page.component.html',
  styleUrl: './master-list-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MasterListPageComponent {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);
  private readonly lookups = inject(LookupService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly messages = inject(MessageService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly destroyRef = inject(DestroyRef);

  /** Supplied through route data. */
  readonly config = input.required<MasterListConfig>();

  private readonly table = viewChild(DataTableComponent);

  protected readonly client = computed(() => new ResourceClient<MasterRow, unknown>(this.http, this.baseUrl + this.config().resource));
  protected readonly active = signal<boolean | null>(true);
  protected readonly params = computed(() => ({ active: this.active() }));
  protected readonly load = (query: PageQuery) => this.client().list(query);
  protected readonly canWrite = computed(() => this.auth.hasPermission(this.config().writePermission));
  protected readonly canReadAudit = computed(() => this.auth.hasPermission(Permissions.auditRead));

  protected readonly dialogOpen = signal(false);
  protected readonly record = signal<MasterRow | null>(null);
  protected readonly form = signal<FormGroup>(new FormGroup({}));
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly lookupOptions = signal<Partial<Record<OptionSource, LookupOption[]>>>({});

  protected readonly readOnly = computed(() => !this.canWrite());
  protected readonly heading = computed(() => {
    const record = this.record();
    return record ? String(record[this.config().identity] ?? '') : '';
  });

  /** Fixed option lists translated for the current language. */
  protected readonly fixedOptions = computed(() => {
    this.language.current();
    const result: Record<string, { label: string; value: string | number }[]> = {};
    for (const field of this.config().fields) {
      if (field.options) {
        result[field.key] = field.options.map((o) => ({ label: this.translate.instant(o.labelKey), value: o.value }));
      }
    }
    return result;
  });

  protected readonly actions = computed<RowAction<MasterRow>[]>(() => [{
    icon: this.canWrite() ? 'pi pi-pencil' : 'pi pi-eye',
    labelKey: this.canWrite() ? 'common.edit' : 'common.view',
    run: (row) => this.open(row),
  }, {
    icon: 'pi pi-trash',
    labelKey: 'common.delete',
    severity: 'danger',
    permission: this.config().writePermission,
    run: (row) => this.confirmDelete(row),
  }]);

  constructor() {
    effect(() => {
      const sources = new Set(this.config().fields.map((f) => f.optionsFrom).filter((s): s is OptionSource => !!s));
      untracked(() => sources.forEach((source) => this.loadOptions(source)));
    });
  }

  protected optionsFor(field: MasterField): { label: string; value: string | number }[] {
    return field.optionsFrom
      ? (this.lookupOptions()[field.optionsFrom] ?? []).map((o) => ({ label: o.label, value: o.value }))
      : (this.fixedOptions()[field.key] ?? []);
  }

  protected open(row: MasterRow | null): void {
    this.record.set(row);
    this.errorKey.set(null);
    const controls: Record<string, FormControl> = {};
    for (const field of this.config().fields) {
      const value = row ? fromApi(field, row[field.key]) : (field.defaultValue ?? null);
      const control = new FormControl(value, validatorsFor(field));
      if (this.readOnly() || (row && field.immutable)) control.disable();
      controls[field.key] = control;
    }
    this.form.set(new FormGroup(controls));
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const form = this.form();
    if (form.invalid || this.saving()) {
      form.markAllAsTouched();
      return;
    }
    const raw = form.getRawValue() as Record<string, unknown>;
    const body: Record<string, unknown> = {};
    for (const field of this.config().fields) body[field.key] = toApi(field, raw[field.key]);

    const current = this.record();
    this.saving.set(true);
    this.errorKey.set(null);
    const call = current ? this.client().update(current.id, current.version, body) : this.client().create(body);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.done(),
      error: (error: unknown) => this.fail(error),
    });
  }

  protected changeStatus(reason: string | null): void {
    const current = this.record();
    if (!current) return;
    this.saving.set(true);
    this.client().changeStatus(current.id, current.version, { active: !current.active, reason })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.done();
        },
        error: (error: unknown) => {
          this.fail(error);
        },
      });
  }

  private done(): void {
    this.saving.set(false);
    this.dialogOpen.set(false);
    this.lookups.invalidate();
    this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
    this.table()?.reload();
  }

  protected confirmDelete(row: MasterRow): void {
    this.confirmation.confirm({
      header: this.translate.instant('common.delete'),
      message: this.translate.instant('common.confirmDelete'),
      acceptLabel: this.translate.instant('common.delete'),
      rejectLabel: this.translate.instant('common.cancel'),
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.saving.set(true);
        this.client().delete(row.id, row.version).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => {
            this.saving.set(false);
            this.dialogOpen.set(false);
            this.lookups.invalidate();
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            this.table()?.reload();
          },
          error: (error: unknown) => {
            this.saving.set(false);
            const key = problemKey(toProblem(error));
            if (this.dialogOpen()) this.errorKey.set(key);
            this.messages.add({ severity: 'error', summary: this.translate.instant(key) });
          },
        });
      },
    });
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    const problem = toProblem(error);
    if (!applyServerErrors(this.form(), problem)) this.errorKey.set(problemKey(problem));
  }

  private loadOptions(source: OptionSource): void {
    const lists: Record<OptionSource, () => Observable<LookupOption[]>> = {
      currencies: () => this.lookups.currencies(),
      units: () => this.lookups.units(),
    };
    (lists[source]?.() ?? of([])).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (options) => this.lookupOptions.update((all) => ({ ...all, [source]: options })),
      error: () => undefined,
    });
  }
}

function validatorsFor(field: MasterField): ValidatorFn[] {
  return [
    ...(field.required ? [Validators.required] : []),
    ...(field.maxLength ? [Validators.maxLength(field.maxLength)] : []),
    ...(field.pattern ? [Validators.pattern(field.pattern)] : []),
    ...(field.min !== undefined ? [Validators.min(field.min)] : []),
    ...(field.max !== undefined ? [Validators.max(field.max)] : []),
    ...(field.validators ?? []),
  ];
}

/** API value → form value (dates arrive as `YYYY-MM-DD`, decimals as numbers). */
function fromApi(field: MasterField, value: unknown): unknown {
  if (value === null || value === undefined) return null;
  if (field.type === 'date') return new Date(`${value}T00:00:00`);
  if (field.type === 'decimal') return Number(value);
  return value;
}

/** Form value → API value: ISO dates, decimals as strings (exact), trimmed text, empty → null. */
function toApi(field: MasterField, value: unknown): unknown {
  if (value === null || value === undefined || value === '') return null;
  switch (field.type) {
    case 'date': {
      const date = value as Date;
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    }
    case 'decimal':
      return String(value);
    case 'text':
    case 'textarea': {
      const text = String(value).trim();
      return text === '' ? null : field.uppercase ? text.toUpperCase() : text;
    }
    default:
      return value;
  }
}
