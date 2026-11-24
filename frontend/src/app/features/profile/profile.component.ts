import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { AuthService, OwnProfile } from '../../core/auth/auth.service';
import { problemKey, toProblem } from '../../core/api/api.models';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

@Component({
  selector: 's2s-profile',
  imports: [ReactiveFormsModule, TranslatePipe, Button, InputText, Message, FormFieldComponent, PageHeaderComponent],
  templateUrl: './profile.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileComponent {
  private readonly auth = inject(AuthService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  private version: number | null = null;

  protected readonly form = this.fb.group({
    username: ['', [Validators.required, Validators.maxLength(128)]],
    fullName: ['', [Validators.maxLength(150)]],
    workEmail: ['', [Validators.email, Validators.maxLength(254)]],
    department: ['', [Validators.maxLength(80)]],
    position: ['', [Validators.maxLength(80)]],
  });

  constructor() { this.load(); }

  protected load(): void {
    this.loading.set(true);
    this.errorKey.set(null);
    this.auth.profile().subscribe({
      next: profile => { this.show(profile); this.loading.set(false); },
      error: error => { this.errorKey.set(problemKey(toProblem(error))); this.loading.set(false); },
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving() || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.errorKey.set(null);
    this.auth.updateProfile(this.version, {
      username: value.username.trim(),
      fullName: value.fullName.trim() || null,
      workEmail: value.workEmail.trim() || null,
      department: value.department.trim() || null,
      position: value.position.trim() || null,
    }).subscribe({
      next: profile => {
        this.show(profile);
        this.saving.set(false);
        this.auth.refreshUser().subscribe({ error: () => undefined });
        this.messages.add({ severity: 'success', summary: this.translate.instant('users.profileSaved') });
      },
      error: error => {
        this.saving.set(false);
        const problem = toProblem(error);
        if (!applyServerErrors(this.form, problem)) this.errorKey.set(problemKey(problem));
      },
    });
  }

  private show(profile: OwnProfile): void {
    this.version = profile.version;
    this.form.reset({
      username: profile.externalSubjectId ?? '',
      fullName: profile.fullName ?? '',
      workEmail: profile.workEmail ?? '',
      department: profile.department ?? '',
      position: profile.position ?? '',
    });
  }
}
