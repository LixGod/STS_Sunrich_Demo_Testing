import { HttpClient, HttpContext } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, model, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { API_BASE_URL, SILENT_ERRORS, problemKey, toProblem } from '../../../core/api/api.models';
import { LookupService } from '../../../core/lookups/lookup.service';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { COUNTRIES, PHONE_COUNTRIES, combinePhone, dialCodeFor } from '../../../shared/data/countries';

interface CreatedClient {
  client: { id: string; legalName: string; code: string; countryCode: string | null; phone: string | null; phones?: string[] };
  contact: { id: string; email: string; phone: string | null };
}

@Component({
  selector: 's2s-client-quick-add',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Dialog, InputText, Message, Select, FormFieldComponent],
  template: `
    <p-dialog [(visible)]="visible" [modal]="true" [draggable]="false" [style]="{ width: 'min(32rem, 96vw)' }" [breakpoints]="{ '640px': '100vw' }" [header]="'enquiry.addClient' | translate">
      @if (errorKey(); as key) { <p-message severity="error" role="alert" [text]="key | translate" /> }
      <form [formGroup]="form" (ngSubmit)="save()" novalidate>
        <s2s-form-field labelKey="masters.name" fieldId="quick-client-name" [control]="form.controls.legalName" [required]="true">
          <input pInputText id="quick-client-name" name="legalName" formControlName="legalName" maxlength="200" autocomplete="organization" />
        </s2s-form-field>
        <s2s-form-field labelKey="masters.client.email" fieldId="quick-client-email" [control]="form.controls.email" [required]="true">
          <input pInputText id="quick-client-email" name="email" formControlName="email" maxlength="254" type="email" autocomplete="email" />
        </s2s-form-field>
        <s2s-form-field labelKey="masters.client.country" fieldId="quick-client-country" [control]="form.controls.countryCode">
          <p-select
            inputId="quick-client-country"
            name="countryCode"
            formControlName="countryCode"
            [options]="countries"
            optionLabel="label"
            optionValue="code"
            [filter]="true"
            filterBy="label,code"
            [showClear]="true"
            appendTo="body"
            [placeholder]="'common.select' | translate" />
        </s2s-form-field>
        <s2s-form-field labelKey="masters.client.phone" fieldId="quick-client-phone" [control]="form.controls.phone">
          <div class="phone-row">
            <p-select
              class="phone-code"
              inputId="quick-client-phone-code"
              name="phoneCountry"
              formControlName="phoneCountry"
              [options]="phoneCountries"
              optionLabel="phoneLabel"
              optionValue="code"
              [filter]="true"
              filterBy="phoneLabel,code,dialCode"
              [showClear]="true"
              appendTo="body"
              [placeholder]="'enquiry.phoneCountryCode' | translate"
              [ariaLabel]="'enquiry.phoneCountryCode' | translate"
              (onChange)="onPhoneCountryChange()">
              <ng-template #selectedItem let-selected>{{ selected.code }} {{ selected.dialCode }}</ng-template>
              <ng-template #item let-option>{{ option.phoneLabel }}</ng-template>
            </p-select>
            <input
              pInputText
              id="quick-client-phone"
              name="phone"
              class="phone-number"
              formControlName="phone"
              type="tel"
              inputmode="tel"
              autocomplete="tel-national"
              maxlength="40" />
          </div>
        </s2s-form-field>
      </form>
      <ng-template #footer>
        <p-button type="button" [label]="'common.cancel' | translate" [text]="true" severity="secondary" (onClick)="visible.set(false)" />
        <p-button type="button" [label]="'common.save' | translate" icon="pi pi-check" [loading]="saving()" (onClick)="save()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    :host ::ng-deep s2s-form-field > p-select,
    :host ::ng-deep s2s-form-field > .p-select,
    :host ::ng-deep s2s-form-field > input {
      width: 100%;
    }
    .phone-row {
      display: flex;
      gap: 0.5rem;
      align-items: stretch;
    }
    .phone-code {
      flex: 0 0 8.5rem;
      width: 8.5rem;
      min-width: 0;
    }
    .phone-number {
      flex: 1 1 auto;
      min-width: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClientQuickAddComponent {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/masters/clients/quick-add`;
  private readonly lookups = inject(LookupService);
  private readonly destroyRef = inject(DestroyRef);
  private phoneCountryManual = false;

  readonly visible = model(false);
  readonly created = output<CreatedClient>();
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly countries = COUNTRIES;
  protected readonly phoneCountries = PHONE_COUNTRIES;

  protected readonly form = inject(NonNullableFormBuilder).group({
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    countryCode: new FormControl<string | null>(null),
    phoneCountry: new FormControl<string | null>(null),
    phone: ['', [(c: AbstractControl) => this.phoneErrors(c)]],
  });

  constructor() {
    effect(() => {
      if (this.visible()) untracked(() => {
        this.phoneCountryManual = false;
        this.form.reset();
        this.errorKey.set(null);
      });
    });

    this.form.controls.countryCode.valueChanges.pipe(takeUntilDestroyed()).subscribe((code) => {
      if (!this.phoneCountryManual) {
        this.form.controls.phoneCountry.setValue(dialCodeFor(code) ? code : null);
      }
    });

    this.form.controls.phoneCountry.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.form.controls.phone.updateValueAndValidity({ emitEvent: false });
    });
  }

  protected onPhoneCountryChange(): void {
    this.phoneCountryManual = true;
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) { this.form.markAllAsTouched(); return; }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.http.post<CreatedClient>(this.url, {
      legalName: value.legalName.trim(),
      email: value.email.trim(),
      countryCode: value.countryCode || null,
      phone: combinePhone(value.phoneCountry, value.phone),
    }, {
      context: new HttpContext().set(SILENT_ERRORS, true),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.lookups.invalidate();
        this.created.emit(created);
        this.visible.set(false);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        const problem = toProblem(error);
        if (!applyServerErrors(this.form, problem)) this.errorKey.set(problemKey(problem));
      },
    });
  }

  private phoneErrors(control: AbstractControl): ValidationErrors | null {
    const raw = String(control.value ?? '').trim();
    if (!raw) return null;
    if (!/^[0-9+()\-. ]+$/.test(raw) || !/\d/.test(raw)) return { pattern: true };
    const combined = combinePhone(this.form?.controls.phoneCountry.value, raw);
    return combined && combined.length > 40 ? { maxlength: { requiredLength: 40, actualLength: combined.length } } : null;
  }
}
