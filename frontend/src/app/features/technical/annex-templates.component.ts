import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { Observable } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { AnnexSection, AnnexTemplate, JpoService } from './jpo.service';

type SectionGroup = FormGroup<{ code: FormControl<string>; title: FormControl<string>; body: FormControl<string>; appliesWhen: FormControl<string> }>;

/**
 * JPO annex wording. The current text is included when a plan is completed; that plan keeps a frozen copy.
 */
@Component({
  selector: 's2s-annex-templates',
  imports: [
    ReactiveFormsModule, TranslatePipe, Button, InputText, Message, Select, Textarea, EmptyStateComponent, FormFieldComponent,
    PageHeaderComponent, StatusTagComponent, UtcDateTimePipe,
  ],
  templateUrl: './annex-templates.component.html',
  styleUrl: './jpo-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnnexTemplatesComponent {
  private readonly service = inject(JpoService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly templates = signal<AnnexTemplate[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  private readonly changes = signal(0);

  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.masterTechnicalWrite));
  protected readonly selected = computed<AnnexTemplate | null>(() => this.templates().find((t) => t.id === this.selectedId()) ?? this.templates()[0] ?? null);
  protected readonly editable = computed(() => this.canWrite() && this.selected()?.status === 'DRAFT');
  protected readonly hasDraft = computed(() => this.templates().some((t) => t.status === 'DRAFT'));
  protected readonly dirty = computed(() => (this.changes(), this.form.dirty));

  protected readonly conditionOptions = computed(() => {
    this.language.current();
    return ['ALWAYS', 'REVERSE_LIGHTERING', 'SIMILAR_LOA'].map((value) => ({ value, label: this.translate.instant(`annex.condition.${value}`) }));
  });

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    sections: new FormArray<SectionGroup>([]),
  });

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
    this.load();
  }

  protected select(id: string): void {
    this.selectedId.set(id);
    this.show();
  }

  protected conditionLabel(code: string): string {
    return this.translate.instant(`annex.condition.${code}`);
  }

  protected addSection(): void {
    this.form.controls.sections.push(sectionGroup({ code: '', title: '', body: '', appliesWhen: 'ALWAYS' }));
    this.form.markAsDirty();
  }

  protected removeSection(index: number): void {
    this.form.controls.sections.removeAt(index);
    this.form.markAsDirty();
  }

  protected newDraft(): void {
    this.run(this.service.newAnnexDraft());
  }

  protected save(): void {
    const template = this.selected();
    if (!template || this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.run(this.service.saveAnnex(template, {
      name: v.name.trim(),
      sections: v.sections.map((s) => ({ code: s.code.trim(), title: s.title.trim(), body: s.body, appliesWhen: s.appliesWhen })),
    }), this.form);
  }

  private load(select?: string): void {
    this.service.annexTemplates().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => {
        this.templates.set(list);
        if (select) this.selectedId.set(select);
        this.show();
      },
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private show(): void {
    const template = this.selected();
    const sections = this.form.controls.sections;
    sections.clear({ emitEvent: false });
    (template?.sections ?? []).forEach((s) => sections.push(sectionGroup(s), { emitEvent: false }));
    this.form.reset({ name: template?.name ?? '' });
    if (this.editable()) this.form.enable({ emitEvent: false });
    else this.form.disable({ emitEvent: false });
    this.changes.update((n) => n + 1);
  }

  private run(call: Observable<AnnexTemplate>, form?: FormGroup): void {
    this.saving.set(true);
    this.errorKey.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.load(result.id);
      },
      error: (error: unknown) => this.fail(toProblem(error), form),
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    this.errorKey.set(form && applyServerErrors(form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
  }
}

function sectionGroup(s: AnnexSection): SectionGroup {
  return new FormGroup({
    code: new FormControl(s.code, { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9-]{0,39}$/)] }),
    title: new FormControl(s.title, { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    body: new FormControl(s.body, { nonNullable: true, validators: Validators.maxLength(100000) }),
    appliesWhen: new FormControl(s.appliesWhen, { nonNullable: true }),
  });
}
