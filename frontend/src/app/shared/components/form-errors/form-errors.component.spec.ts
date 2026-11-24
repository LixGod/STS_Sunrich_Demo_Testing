import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { provideTranslateService } from '@ngx-translate/core';
import { FormErrorsComponent, applyServerErrors, firstMessage } from './form-errors.component';

describe('form errors', () => {
  it('maps validator errors to translation keys with parameters', () => {
    expect(firstMessage({ required: true })).toEqual({ key: 'validation.required' });
    expect(firstMessage({ maxlength: { requiredLength: 20, actualLength: 25 } })).toEqual({
      key: 'validation.maxlength',
      params: { length: 20 },
    });
    expect(firstMessage({ server: 'Unknown legal entity.' })).toEqual({ key: 'validation.server', text: 'Unknown legal entity.' });
  });

  it('places server field errors on matching controls only', () => {
    const form = new FormGroup({ code: new FormControl('X'), name: new FormControl('Y') });
    const applied = applyServerErrors(form, {
      status: 409,
      code: 'DUPLICATE_CODE',
      errors: [
        { field: 'code', code: 'duplicate', message: 'Already used' },
        { field: 'unknown', code: 'x' },
      ],
    });
    expect(applied).toBe(true);
    expect(form.controls.code.errors).toEqual({ server: 'Already used' });
    expect(form.controls.name.errors).toBeNull();
  });

  it('stays silent until the control is touched, then shows the message', async () => {
    TestBed.configureTestingModule({ providers: [provideTranslateService()] });
    const control = new FormControl('', Validators.required);
    const fixture = TestBed.createComponent(FormErrorsComponent);
    fixture.componentRef.setInput('control', control);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent.trim()).toBe('');

    control.markAsTouched();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent.trim()).toBe('validation.required');
  });
});
