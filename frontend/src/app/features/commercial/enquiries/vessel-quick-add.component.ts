import { HttpClient, HttpContext } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, model, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { API_BASE_URL, SILENT_ERRORS, problemKey, toProblem } from '../../../core/api/api.models';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { imoValidator } from '../../../shared/validators/imo.validator';

const VESSEL_TYPES = ['CRUDE_TANKER', 'PRODUCT_TANKER', 'CHEMICAL_TANKER', 'LPG_CARRIER', 'LNG_CARRIER', 'BULK_CARRIER', 'OTHER'];

/**
 * Registers a vessel by IMO, name and type from inside the enquiry form. Particulars stay UNVERIFIED and are completed
 * later in the vessel master / technical review.
 */
@Component({
  selector: 's2s-vessel-quick-add',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Dialog, InputText, Message, Select, FormFieldComponent],
  templateUrl: './vessel-quick-add.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VesselQuickAddComponent {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/masters/vessels`;
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly lookups = inject(LookupService);
  private readonly destroyRef = inject(DestroyRef);

  readonly visible = model(false);
  readonly created = output<LookupOption>();

  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly typeOptions = computed(() => {
    this.language.current();
    return VESSEL_TYPES.map((value) => ({ value, label: this.translate.instant(`enums.vesselType.${value}`) }));
  });

  protected readonly form = inject(NonNullableFormBuilder).group({
    imo: ['', [Validators.required, imoValidator]],
    name: ['', [Validators.required, Validators.maxLength(150)]],
    vesselType: ['', [Validators.required]],
  });

  constructor() {
    // Clear the form each time the dialog opens. Untracked: reset() reads the controls' touched/pristine signals,
    // and tracking those would re-run this on every blur and wipe what the user entered.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.form.reset();
          this.errorKey.set(null);
        });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.http
      .post<{ id: string; imo: string; name: string }>(this.url, { ...value, imo: value.imo.trim(), name: value.name.trim() }, {
        context: new HttpContext().set(SILENT_ERRORS, true),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (vessel) => {
          this.saving.set(false);
          this.lookups.invalidate();
          this.created.emit({ value: vessel.id, label: `${vessel.name} · IMO ${vessel.imo}`, meta: { imo: vessel.imo } });
          this.visible.set(false);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const problem = toProblem(error);
          if (!applyServerErrors(this.form, problem)) this.errorKey.set(problemKey(problem));
        },
      });
  }
}
