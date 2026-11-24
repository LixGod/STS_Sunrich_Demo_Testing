import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { AbstractControl, FormGroup, ValidationErrors } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Problem } from '../../../core/api/api.models';

/** Shows the first validation error of a control once the user has touched it (or the form was submitted). */
@Component({
  selector: 's2s-form-errors',
  imports: [TranslatePipe],
  templateUrl: './form-errors.component.html',
  styleUrl: './form-errors.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormErrorsComponent {
  readonly control = input.required<AbstractControl>();
  readonly errorId = input<string | undefined>(undefined);

  /** Bumped on every status/value/touch change so the computed below re-evaluates. */
  private readonly tick = signal(0);

  protected readonly message = computed(() => {
    this.tick();
    const control = this.control();
    if (!control.errors || !(control.touched || control.dirty)) return null;
    return firstMessage(control.errors);
  });

  constructor() {
    effect((onCleanup) => {
      const subscription = this.control().events.subscribe(() => this.tick.update((n) => n + 1));
      onCleanup(() => subscription.unsubscribe());
    });
  }
}

export interface ErrorMessage {
  key: string;
  params?: Record<string, unknown>;
  text?: string;
}

export function firstMessage(errors: ValidationErrors): ErrorMessage {
  const [code, detail] = Object.entries(errors)[0];
  switch (code) {
    case 'server':
      return { key: 'validation.server', text: String(detail) };
    case 'maxlength':
    case 'minlength':
      return { key: `validation.${code}`, params: { length: (detail as { requiredLength: number }).requiredLength } };
    case 'min':
      return { key: 'validation.min', params: { min: (detail as { min: number }).min } };
    case 'max':
      return { key: 'validation.max', params: { max: (detail as { max: number }).max } };
    default:
      return { key: `validation.${code}` };
  }
}

/**
 * Copies field errors from a server Problem onto matching form controls (as `{ server: message }`).
 * Returns true if at least one error was placed on a field.
 */
export function applyServerErrors(form: FormGroup, problem: Problem): boolean {
  let applied = false;
  for (const error of problem.errors ?? []) {
    // `lines[0].quantity` from the server is `lines.0.quantity` in a reactive form.
    const control = form.get(error.field.replace(/\[(\d+)\]/g, '.$1'));
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: error.message ?? error.code });
      control.markAsTouched();
      applied = true;
    }
  }
  return applied;
}
