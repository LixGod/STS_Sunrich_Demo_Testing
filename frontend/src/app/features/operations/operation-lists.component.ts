import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { problemKey, toProblem } from '../../core/api/api.models';
import { LanguageService } from '../../core/i18n/language.service';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { ListItem, OperationsList, OperationsService } from './operations.service';

const LISTS: OperationsList[] = ['event-types', 'delay-categories', 'incident-categories', 'incident-severities'];

/** The operations lists (event types, delay and incident categories, severities), edited by Operations managers. */
@Component({
  selector: 's2s-operation-lists',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Checkbox, Dialog, InputText, Message, Select, FormFieldComponent, PageHeaderComponent,
    StatusTagComponent],
  templateUrl: './operation-lists.component.html',
  styleUrl: './operations-console.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OperationListsComponent {
  private readonly service = inject(OperationsService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly lists = LISTS;
  protected readonly current = signal<OperationsList>('event-types');
  protected readonly items = signal<ListItem[]>([]);
  protected readonly editing = signal<ListItem | null>(null);
  protected readonly open = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly isEventTypes = computed(() => this.current() === 'event-types');
  protected readonly phaseOptions = computed(() => {
    this.language.current();
    return ['BERTHING', 'TRANSFER', 'COMPLETION', 'UNMOORING', 'OTHER'].map((value) => ({ value, label: this.translate.instant(`ops.phase.${value}`) }));
  });

  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]{1,39}$/)] }),
    label: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    sortOrder: new FormControl(0, { nonNullable: true, validators: [Validators.min(0), Validators.max(9999)] }),
    active: new FormControl(true, { nonNullable: true }),
    phase: new FormControl('OTHER', { nonNullable: true }),
    interval: new FormControl(false, { nonNullable: true }),
  });

  constructor() {
    this.load();
  }

  protected select(list: OperationsList): void {
    this.current.set(list);
    this.load();
  }

  protected edit(item: ListItem | null): void {
    this.editing.set(item);
    this.dialogError.set(null);
    const next = (this.items().at(-1)?.sortOrder ?? 0) + 10;
    this.form.reset({ code: item?.code ?? '', label: item?.label ?? '', sortOrder: item?.sortOrder ?? next, active: item?.active ?? true,
      phase: item?.phase ?? 'OTHER', interval: item?.interval ?? false });
    if (item) this.form.controls.code.disable();
    else this.form.controls.code.enable();
    this.open.set(true);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const input = { code: v.code.trim().toUpperCase(), label: v.label.trim(), sortOrder: Number(v.sortOrder), active: v.active,
      phase: this.isEventTypes() ? v.phase : null, interval: this.isEventTypes() ? v.interval : null };
    this.saving.set(true);
    this.service.saveListItem(this.current(), input, this.editing() ?? undefined).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.open.set(false);
        this.load();
      },
      error: (e: unknown) => {
        this.saving.set(false);
        const problem = toProblem(e);
        this.dialogError.set(applyServerErrors(this.form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
      },
    });
  }

  private load(): void {
    this.service.list(this.current()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (items) => this.items.set(items),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }
}
