import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Textarea } from 'primeng/textarea';
import { problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { Branch, BranchRequest, BranchService, timeZoneOptions } from '../organisation.service';

@Component({
  selector: 's2s-branch-form',
  imports: [
    ReactiveFormsModule, TranslatePipe, Button, InputText, Message, Select, Textarea,
    FormFieldComponent, PageHeaderComponent, StatusTagComponent, AuditTrailComponent,
  ],
  templateUrl: './branch-form.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchFormComponent {
  private readonly service = inject(BranchService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly translate = inject(TranslateService);

  readonly id = input<string | undefined>(undefined);

  protected readonly timeZones = timeZoneOptions();
  protected readonly record = signal<Branch | null>(null);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.masterOrgWrite));
  protected readonly canReadAudit = computed(() => this.auth.hasPermission(Permissions.auditRead));

  protected readonly form = inject(NonNullableFormBuilder).group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Z0-9][A-Z0-9-]{1,19}$/)]],
    name: ['', [Validators.required, Validators.maxLength(150)]],
    timezone: ['', [Validators.required]],
    address: ['', [Validators.maxLength(500)]],
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => (id ? this.load(id) : this.applyAccess()));
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: BranchRequest = { ...value, code: value.code.trim().toUpperCase(), address: value.address || null };
    const current = this.record();
    this.saving.set(true);
    this.errorKey.set(null);
    const call = current ? this.service.update(current.id, current.version, request) : this.service.create(request);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
        if (!current) {
          void this.router.navigate(['/administration/branches', saved.id], { replaceUrl: true });
        } else {
          this.show(saved);
        }
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  protected changeStatus(reason: string | null): void {
    const current = this.record();
    if (!current) return;
    this.saving.set(true);
    this.service.changeStatus(current.id, current.version, { active: !current.active, reason }).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.show(saved);
      },
      error: (error: unknown) => {
        this.fail(error);
      },
    });
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
        this.service.delete(current.id, current.version).subscribe({
          next: () => {
            this.saving.set(false);
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            void this.router.navigate(['/administration/branches']);
          },
          error: (error: unknown) => this.fail(error),
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
    this.errorKey.set(null);
    this.service.get(id).subscribe({
      next: (branch) => {
        this.loading.set(false);
        this.show(branch);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    });
  }

  private show(branch: Branch): void {
    this.record.set(branch);
    this.form.reset({
      code: branch.code,
      name: branch.name,
      timezone: branch.timezone,
      address: branch.address ?? '',
    });
    this.applyAccess();
  }

  /** The code is fixed once created; read-only without write permission. */
  private applyAccess(): void {
    if (!this.canWrite()) {
      this.form.disable();
    } else if (this.record()) {
      this.form.controls.code.disable();
    }
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    const problem = toProblem(error);
    if (!applyServerErrors(this.form, problem)) {
      this.errorKey.set(problemKey(problem));
    }
  }
}
