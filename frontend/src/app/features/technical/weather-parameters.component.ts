import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { WeatherParameter, WeatherService } from './weather.service';

/** Weather parameter catalogue: the quantities operating limits and weather values use. No values are set here. */
@Component({
  selector: 's2s-weather-parameters',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Checkbox, Dialog, InputText, Message, FormFieldComponent, PageHeaderComponent, StatusTagComponent],
  templateUrl: './weather-parameters.component.html',
  styleUrl: './weather-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WeatherParametersComponent {
  private readonly service = inject(WeatherService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly parameters = signal<WeatherParameter[]>([]);
  protected readonly editing = signal<WeatherParameter | null>(null);
  protected readonly open = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.masterTechnicalWrite));

  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]{1,39}$/)] }),
    label: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    unit: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(20)] }),
    sortOrder: new FormControl(0, { nonNullable: true, validators: [Validators.min(0), Validators.max(9999)] }),
    active: new FormControl(true, { nonNullable: true }),
  });

  constructor() {
    this.load();
  }

  protected edit(parameter: WeatherParameter | null): void {
    this.editing.set(parameter);
    this.dialogError.set(null);
    const next = (this.parameters().at(-1)?.sortOrder ?? 0) + 10;
    this.form.reset(parameter ?? { code: '', label: '', unit: '', sortOrder: next, active: true });
    if (parameter) this.form.controls.code.disable();
    else this.form.controls.code.enable();
    this.open.set(true);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.saveParameter({ ...v, code: v.code.trim().toUpperCase(), sortOrder: Number(v.sortOrder) }, this.editing() ?? undefined)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => {
          this.saving.set(false);
          this.open.set(false);
          this.load();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const problem = toProblem(error);
          this.dialogError.set(applyServerErrors(this.form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
        },
      });
  }

  private load(): void {
    this.service.parameters().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => this.parameters.set(list),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }
}
