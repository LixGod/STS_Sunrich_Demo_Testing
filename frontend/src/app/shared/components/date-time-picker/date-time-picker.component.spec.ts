import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { provideTranslateService } from '@ngx-translate/core';
import { DateTimePickerComponent } from './date-time-picker.component';

@Component({
  imports: [ReactiveFormsModule, DateTimePickerComponent],
  template: `<s2s-date-time-picker inputId="when" [formControl]="control" [clearable]="true" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class HostComponent {
  readonly control = new FormControl<Date | null>(new Date(2026, 9, 16, 15, 44));
}

@Component({
  imports: [ReactiveFormsModule, DateTimePickerComponent],
  template: `<s2s-date-time-picker inputId="local" [formControl]="control" [localString]="true" [clearable]="true" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class LocalHostComponent {
  readonly control = new FormControl('2026-10-16T15:44', { nonNullable: true });
}

describe('DateTimePickerComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideTranslateService()] }));

  it('shows the form value in the input and follows the control disabled state', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#when');
    expect(input.value).toBe('2026-10-16 15:44');

    fixture.componentInstance.control.disable();
    await fixture.whenStable();
    expect(input.disabled).toBe(true);
  });

  it('offers Apply and Clear in the open panel; Clear empties the value and marks the field touched', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    (fixture.nativeElement.querySelector('#when') as HTMLInputElement).click();
    await fixture.whenStable();

    const buttons = [...document.querySelectorAll<HTMLButtonElement>('.picker-footer button')];
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['common.clear', 'common.apply']);

    buttons[0].click();
    await fixture.whenStable();
    expect(fixture.componentInstance.control.value).toBeNull();
    expect(fixture.componentInstance.control.touched).toBe(true);
  });

  it('keeps a picked time out of the form until Apply and discards a dismissed pick', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const picker = fixture.debugElement.query(By.directive(DateTimePickerComponent)).componentInstance as DateTimePickerComponent;
    const original = fixture.componentInstance.control.value;
    picker['changed'](new Date(2026, 9, 17, 9, 0));
    expect(fixture.componentInstance.control.value).toEqual(original);
    picker['cancel']();
    expect(picker['draft']()).toEqual(original);
    picker['changed'](new Date(2026, 9, 17, 9, 0));
    picker['apply']();
    expect(fixture.componentInstance.control.value).toEqual(new Date(2026, 9, 17, 9, 0));
  });

  it('commits local wall-clock strings only on Apply and clears to an empty string', async () => {
    const fixture = TestBed.createComponent(LocalHostComponent);
    await fixture.whenStable();
    const picker = fixture.debugElement.query(By.directive(DateTimePickerComponent)).componentInstance as DateTimePickerComponent;
    expect((fixture.nativeElement.querySelector('#local') as HTMLInputElement).value).toBe('2026-10-16 15:44');
    picker['changed'](new Date(2026, 9, 17, 9, 5));
    expect(fixture.componentInstance.control.value).toBe('2026-10-16T15:44');
    picker['apply']();
    expect(fixture.componentInstance.control.value).toBe('2026-10-17T09:05');
    picker['clear']();
    expect(fixture.componentInstance.control.value).toBe('');
  });
});
