import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Password } from 'primeng/password';
import { toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';

@Component({
  selector: 's2s-login',
  imports: [ReactiveFormsModule, TranslatePipe, Button, InputText, Password, Message, FormFieldComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Query parameters bound by the router. */
  readonly returnUrl = input<string | undefined>(undefined);
  readonly expired = input<string | undefined>(undefined);

  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', [Validators.required, Validators.maxLength(150)]],
    password: ['', [Validators.required, Validators.maxLength(200)]],
  });
  protected readonly submitting = signal(false);
  protected readonly errorKey = signal<string | null>(null);

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.errorKey.set(null);
    const { username, password } = this.form.getRawValue();
    this.auth.login(username.trim(), password).subscribe({
      next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
      error: (error: unknown) => {
        this.submitting.set(false);
        this.form.controls.password.reset();
        this.errorKey.set(loginErrorKey(toProblem(error).code));
      },
    });
  }
}

/** Only same-app paths are allowed as a return target (no open redirects). */
export function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login') ? url : '/dashboard';
}

function loginErrorKey(code: string): string {
  switch (code) {
    case 'INVALID_CREDENTIALS':
      return 'auth.invalidCredentials';
    case 'ACCOUNT_NOT_ACTIVE':
      return 'auth.accountNotActive';
    case 'AUTH_PROVIDER_UNAVAILABLE':
      return 'auth.providerUnavailable';
    case 'NETWORK_ERROR':
      return 'errors.network';
    default:
      return 'auth.signInFailed';
  }
}
