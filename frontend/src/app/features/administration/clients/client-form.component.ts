import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormArray, FormControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { MultiSelect } from 'primeng/multiselect';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { catchError, of } from 'rxjs';
import { problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { COUNTRIES } from '../../../shared/data/countries';
import { CLIENT_ROLES, Client, ClientContact, ClientRequest, ClientRole, ClientService } from './client.service';

/** Client master record with roles and contacts (SRS §4, §21). */
@Component({
  selector: 's2s-client-form',
  imports: [
    ReactiveFormsModule, TranslatePipe, Button, Checkbox, Dialog, InputText, Message, MultiSelect, Select, Textarea,
    FormFieldComponent, PageHeaderComponent, StatusTagComponent, AuditTrailComponent,
  ],
  templateUrl: './client-form.component.html',
  styleUrl: './client-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClientFormComponent {
  private readonly service = inject(ClientService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly id = input<string | undefined>(undefined);

  protected readonly record = signal<Client | null>(null);
  protected readonly contacts = signal<ClientContact[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.masterCommercialWrite));
  protected readonly canReadAudit = computed(() => this.auth.hasPermission(Permissions.auditRead));
  protected readonly listRoute = '/administration/clients';
  protected readonly currencies = toSignal(inject(LookupService).currencies().pipe(catchError(() => of([]))), { initialValue: [] });
  protected readonly countries = COUNTRIES;
  protected readonly roleOptions = computed(() => {
    this.language.current();
    return CLIENT_ROLES.map((role) => ({ value: role, label: this.translate.instant(`enums.clientRole.${role}`) }));
  });

  protected readonly form = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9-]{1,19}$/)]],
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    roles: new FormControl<ClientRole[]>([], { nonNullable: true, validators: [Validators.required] }),
    taxRegistrationNo: ['', [Validators.maxLength(50)]],
    countryCode: new FormControl<string | null>(null),
    emails: new FormArray<FormControl<string>>([]),
    phones: new FormArray<FormControl<string>>([]),
    defaultCurrency: new FormControl<string | null>('USD'),
    billingAddress: ['', [Validators.maxLength(500)]],
    paymentTerms: ['', [Validators.maxLength(500)]],
  });

  // Contact dialog
  protected readonly contactDialog = signal(false);
  protected readonly editingContact = signal<ClientContact | null>(null);
  protected readonly contactSaving = signal(false);
  protected readonly contactError = signal<string | null>(null);
  protected readonly contactForm = this.fb.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    position: ['', [Validators.maxLength(100)]],
    email: ['', [Validators.email, Validators.maxLength(254)]],
    phone: ['', [Validators.pattern(/^[0-9+()\-. ]{0,40}$/)]],
    primary: [false],
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => {
        if (id) this.load(id);
        else {
          this.initNewChannels();
          this.applyAccess();
        }
      });
    });
  }

  protected save(): void {
    this.removeBlankChannels();
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: ClientRequest = {
      code: v.code.trim().toUpperCase(),
      legalName: v.legalName.trim(),
      roles: v.roles,
      taxRegistrationNo: v.taxRegistrationNo.trim() || null,
      countryCode: v.countryCode || null,
      phone: v.phones.map((phone) => phone.trim()).find(Boolean) ?? null,
      emails: v.emails.map((email) => email.trim()).filter(Boolean),
      phones: v.phones.map((phone) => phone.trim()).filter(Boolean),
      defaultCurrency: v.defaultCurrency || null,
      billingAddress: v.billingAddress.trim() || null,
      paymentTerms: v.paymentTerms.trim() || null,
    };
    const current = this.record();
    this.saving.set(true);
    this.errorKey.set(null);
    const call = current ? this.service.update(current.id, current.version, request) : this.service.create(request);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
        if (!current) void this.router.navigate([this.listRoute, saved.id], { replaceUrl: true });
        else this.show(saved);
      },
      error: (error: unknown) => this.fail(error, this.form, this.errorKey),
    });
  }

  protected addEmail(): void {
    if (this.form.controls.emails.length < 10) this.form.controls.emails.push(new FormControl('', {
      nonNullable: true, validators: [Validators.email, Validators.maxLength(254)],
    }));
  }

  protected addPhone(): void {
    if (this.form.controls.phones.length < 10) this.form.controls.phones.push(new FormControl('', {
      nonNullable: true, validators: phoneValidator,
    }));
  }

  protected removeEmail(index: number): void { this.form.controls.emails.removeAt(index); }
  protected removePhone(index: number): void { this.form.controls.phones.removeAt(index); }

  protected moveEmail(index: number, by: number): void { this.move(this.form.controls.emails, index, by); }
  protected movePhone(index: number, by: number): void { this.move(this.form.controls.phones, index, by); }

  private move(rows: FormArray<FormControl<string>>, index: number, by: number): void {
    const target = index + by;
    if (target < 0 || target >= rows.length) return;
    const control = rows.at(index);
    rows.removeAt(index);
    rows.insert(target, control);
  }

  protected changeStatus(reason: string | null): void {
    const current = this.record();
    if (!current) return;
    this.saving.set(true);
    this.service.changeStatus(current.id, current.version, { active: !current.active, reason })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.show(saved);
        },
        error: (error: unknown) => {
          this.fail(error, this.form, this.errorKey);
        },
      });
  }

  protected openContact(contact: ClientContact | null): void {
    this.editingContact.set(contact);
    this.contactError.set(null);
    this.contactForm.reset({
      fullName: contact?.fullName ?? '',
      position: contact?.position ?? '',
      email: contact?.email ?? '',
      phone: contact?.phone ?? '',
      primary: contact?.primary ?? this.contacts().length === 0,
    });
    if (!this.canWrite()) this.contactForm.disable();
    else this.contactForm.enable();
    this.contactDialog.set(true);
  }

  protected saveContact(): void {
    const client = this.record();
    if (!client || this.contactForm.invalid || this.contactSaving()) {
      this.contactForm.markAllAsTouched();
      return;
    }
    const v = this.contactForm.getRawValue();
    const request = {
      fullName: v.fullName.trim(),
      position: v.position.trim() || null,
      email: v.email.trim() || null,
      phone: v.phone.trim() || null,
      primary: v.primary,
    };
    const editing = this.editingContact();
    this.contactSaving.set(true);
    const call = editing ? this.service.updateContact(editing, request) : this.service.addContact(client.id, request);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.contactSaving.set(false);
        this.contactDialog.set(false);
        this.loadContacts(client.id);
      },
      error: (error: unknown) => {
        this.contactSaving.set(false);
        const problem = toProblem(error);
        if (!applyServerErrors(this.contactForm, problem)) this.contactError.set(problemKey(problem));
      },
    });
  }

  protected toggleContact(contact: ClientContact): void {
    this.service.changeContactStatus(contact, { active: !contact.active, reason: null })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: () => this.loadContacts(contact.clientId), error: (error: unknown) => this.errorKey.set(problemKey(toProblem(error))) });
  }

  protected confirmDelete(): void {
    const current = this.record();
    if (!current || this.saving()) return;
    this.confirmation.confirm({
      header: this.translate.instant('common.delete'),
      message: this.translate.instant('common.confirmDelete'),
      acceptLabel: this.translate.instant('common.delete'),
      rejectLabel: this.translate.instant('common.cancel'),
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.saving.set(true);
        this.errorKey.set(null);
        this.service.delete(current.id, current.version).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => {
            this.saving.set(false);
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            void this.router.navigate([this.listRoute]);
          },
          error: (error: unknown) => this.fail(error, this.form, this.errorKey),
        });
      },
    });
  }

  protected confirmDeleteContact(contact: ClientContact): void {
    this.confirmation.confirm({
      header: this.translate.instant('common.delete'),
      message: this.translate.instant('common.confirmDelete'),
      acceptLabel: this.translate.instant('common.delete'),
      rejectLabel: this.translate.instant('common.cancel'),
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.service.deleteContact(contact).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => {
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            this.loadContacts(contact.clientId);
          },
          error: (error: unknown) => this.errorKey.set(problemKey(toProblem(error))),
        });
      },
    });
  }

  protected reload(): void {
    const id = this.id();
    if (id) this.load(id);
  }

  private load(id: string): void {
    this.loading.set(true);
    this.service.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (client) => {
        this.loading.set(false);
        this.show(client);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
    this.loadContacts(id);
  }

  private loadContacts(clientId: string): void {
    this.service.contacts(clientId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (contacts) => this.contacts.set(contacts),
      error: () => undefined,
    });
  }

  private show(client: Client): void {
    this.record.set(client);
    this.form.reset({
      code: client.code,
      legalName: client.legalName,
      roles: client.roles,
      taxRegistrationNo: client.taxRegistrationNo ?? '',
      countryCode: client.countryCode,
      defaultCurrency: client.defaultCurrency,
      billingAddress: client.billingAddress ?? '',
      paymentTerms: client.paymentTerms ?? '',
    });
    this.form.controls.emails.clear();
    for (const email of client.emails ?? []) { this.addEmail(); this.form.controls.emails.at(this.form.controls.emails.length - 1).setValue(email); }
    this.form.controls.phones.clear();
    for (const phone of client.phones?.length ? client.phones : client.phone ? [client.phone] : []) {
      this.addPhone(); this.form.controls.phones.at(this.form.controls.phones.length - 1).setValue(phone);
    }
    this.applyAccess();
  }

  private applyAccess(): void {
    if (!this.canWrite()) this.form.disable();
    else if (this.record()) this.form.controls.code.disable();
  }

  private initNewChannels(): void {
    if (this.form.controls.emails.length === 0) this.addEmail();
    if (this.form.controls.phones.length === 0) this.addPhone();
  }

  private removeBlankChannels(): void {
    for (const rows of [this.form.controls.emails, this.form.controls.phones]) {
      for (let i = rows.length - 1; i >= 0; i--) {
        if (!rows.at(i).value.trim()) rows.removeAt(i);
      }
    }
  }

  private fail(error: unknown, form: typeof this.form, target: typeof this.errorKey): void {
    this.saving.set(false);
    const problem = toProblem(error);
    if (!applyServerErrors(form, problem)) target.set(problemKey(problem));
  }
}

function phoneValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '').trim();
  if (!value) return null;
  return /^[0-9+()\-. ]{1,40}$/.test(value) && /\d/.test(value) ? null : { pattern: true };
}
