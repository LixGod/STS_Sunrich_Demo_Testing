import { ChangeDetectionStrategy, Component, effect, input, model, output, untracked } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Textarea } from 'primeng/textarea';
import { FormFieldComponent } from '../form-field/form-field.component';

/** Confirmation dialog that captures a reason (required for deactivation/suspension; recorded in the audit trail). */
@Component({
  selector: 's2s-reason-dialog',
  imports: [ReactiveFormsModule, TranslatePipe, Button, Dialog, Textarea, FormFieldComponent],
  templateUrl: './reason-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReasonDialogComponent {
  readonly visible = model(false);
  readonly headerKey = input.required<string>();
  readonly messageKey = input<string | undefined>(undefined);
  readonly confirmKey = input('common.confirm');
  readonly reasonRequired = input(true);
  readonly saving = input(false);
  readonly confirmed = output<string | null>();

  protected readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

  constructor() {
    // Only the inputs are tracked: reset() reads the control's touched/pristine signals, and tracking those would
    // re-run this on every blur and wipe what the user typed.
    effect(() => {
      const visible = this.visible();
      const required = this.reasonRequired();
      untracked(() => {
        if (visible) {
          this.reason.reset('');
        }
        this.reason.setValidators(required ? [Validators.required, Validators.maxLength(500)] : [Validators.maxLength(500)]);
        this.reason.updateValueAndValidity({ emitEvent: false });
      });
    });
  }

  protected confirm(): void {
    if (this.reason.invalid) {
      this.reason.markAsTouched();
      return;
    }
    this.confirmed.emit(this.reason.value.trim() || null);
  }
}
