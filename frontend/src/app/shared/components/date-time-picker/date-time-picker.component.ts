import { ChangeDetectionStrategy, Component, forwardRef, input, signal, viewChild } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { DatePicker } from 'primeng/datepicker';

/**
 * Date-and-time field whose panel ends with an explicit **Apply** button (and **Clear** for optional fields),
 * so users confirm the date and time they picked instead of having to click outside the panel. Picking a day keeps
 * the panel open (PrimeNG closes it by default), so the time can still be adjusted before Apply.
 * Works with reactive and template-driven forms; emits a Date by default or a local wall-clock string for `localString` fields.
 */
@Component({
  selector: 's2s-date-time-picker',
  imports: [FormsModule, TranslatePipe, Button, DatePicker],
  templateUrl: './date-time-picker.component.html',
  styleUrl: './date-time-picker.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => DateTimePickerComponent), multi: true }],
})
export class DateTimePickerComponent implements ControlValueAccessor {
  /** Id of the text input, so a `<label for>` (e.g. from s2s-form-field) is associated with it. */
  readonly inputId = input.required<string>();
  /** Show a Clear button in the panel (for optional fields). */
  readonly clearable = input(false);
  /** Emit yyyy-MM-ddTHH:mm for forms that store a local wall-clock time. */
  readonly localString = input(false);
  /** 'body' inside dialogs, so the panel is not clipped by the dialog's scroll area. */
  readonly appendTo = input<'body' | null>(null);

  private readonly picker = viewChild.required(DatePicker);

  protected readonly value = signal<Date | null>(null);
  protected readonly draft = signal<Date | null>(null);
  protected readonly disabled = signal(false);

  private onChange: (value: Date | string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: Date | string | null): void {
    const date = typeof value === 'string' ? (value ? new Date(value) : null) : value;
    this.value.set(date && !Number.isNaN(date.getTime()) ? date : null);
    this.draft.set(this.value());
  }

  registerOnChange(fn: (value: Date | string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }

  protected changed(value: Date | null): void {
    this.draft.set(value);
  }

  /** Confirms the picked date and time and closes the panel. */
  protected apply(): void {
    this.value.set(this.draft());
    const date = this.draft();
    const pad = (part: number) => String(part).padStart(2, '0');
    this.onChange(this.localString() ? date
      ? `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
      : '' : date);
    this.onTouched();
    this.picker().hideOverlay();
  }

  protected clear(): void {
    this.changed(null);
    this.apply();
  }

  protected cancel(): void {
    this.draft.set(this.value());
  }

  protected touched(): void {
    this.onTouched();
  }
}
