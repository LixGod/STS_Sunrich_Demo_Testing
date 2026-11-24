import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AbstractControl } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { FormErrorsComponent } from '../form-errors/form-errors.component';

/**
 * Label + projected input + hint + translated validation message, so every form looks and behaves the same.
 * Give the projected input `id="<fieldId>"` so the label is associated with it.
 */
@Component({
  selector: 's2s-form-field',
  imports: [TranslatePipe, FormErrorsComponent],
  templateUrl: './form-field.component.html',
  styleUrl: './form-field.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormFieldComponent {
  readonly labelKey = input.required<string>();
  readonly fieldId = input.required<string>();
  readonly control = input<AbstractControl | null>(null);
  readonly required = input(false);
  readonly hintKey = input<string | undefined>(undefined);
}
