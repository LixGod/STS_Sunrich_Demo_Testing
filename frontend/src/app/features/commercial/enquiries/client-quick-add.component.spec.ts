import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { API_BASE_URL } from '../../../core/api/api.models';
import { COUNTRIES, PHONE_COUNTRIES, combinePhone, dialCodeFor } from '../../../shared/data/countries';
import { ClientQuickAddComponent } from './client-quick-add.component';

describe('countries and phone helper', () => {
  it('provides unique ISO country codes and valid calling codes', () => {
    const codes = COUNTRIES.map((c) => c.code);
    expect(new Set(codes).size).toBe(COUNTRIES.length);
    expect(COUNTRIES.length).toBeGreaterThanOrEqual(245);
    expect(PHONE_COUNTRIES.every((c) => /^\+\d+$/.test(c.dialCode))).toBe(true);
    expect(dialCodeFor('IN')).toBe('+91');
    expect(dialCodeFor('AE')).toBe('+971');
    expect(dialCodeFor('US')).toBe('+1');
    expect(dialCodeFor('GB')).toBe('+44');
  });

  it('combines country calling code and local number without duplicating explicit + prefixes', () => {
    expect(combinePhone('IN', '   ')).toBeNull();
    expect(combinePhone('IN', '9876543210')).toBe('+91 9876543210');
    expect(combinePhone('IN', '+44 20 7946 0958')).toBe('+44 20 7946 0958');
    expect(combinePhone(null, '9876543210')).toBe('9876543210');
  });
});

describe('ClientQuickAddComponent', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [ClientQuickAddComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService(),
        { provide: API_BASE_URL, useValue: '/api/v1' },
      ],
    });
    const fixture = TestBed.createComponent(ClientQuickAddComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: ClientQuickAddComponent['form'];
      save: () => void;
      onPhoneCountryChange: () => void;
    };
    return { fixture, component, http };
  }

  it('allows saving with only name and email while keeping country and phone optional', () => {
    const { component, http } = setup();
    component.form.patchValue({ legalName: 'Acme Marine', email: 'ops@acme.test', countryCode: 'SG' });
    // Even though phoneCountry auto-synced from countryCode, blank phone sends null.
    expect(component.form.controls.phoneCountry.value).toBe('SG');

    component.save();
    const req = http.expectOne('/api/v1/masters/clients/quick-add');
    expect(req.request.body).toEqual({
      legalName: 'Acme Marine',
      email: 'ops@acme.test',
      countryCode: 'SG',
      phone: null,
    });
    http.verify();
  });

  it('auto-syncs phone country code from client country until manually changed and sends combined phone', () => {
    const { component, http } = setup();
    component.form.patchValue({ legalName: 'Gulf Tankers', email: 'desk@gulf.test', countryCode: 'AE' });
    expect(component.form.controls.phoneCountry.value).toBe('AE');

    component.onPhoneCountryChange();
    component.form.patchValue({ phoneCountry: 'GB', countryCode: 'IN', phone: '20 7946 0958' });
    expect(component.form.controls.phoneCountry.value).toBe('GB');

    component.save();
    const req = http.expectOne('/api/v1/masters/clients/quick-add');
    expect(req.request.body).toEqual({
      legalName: 'Gulf Tankers',
      email: 'desk@gulf.test',
      countryCode: 'IN',
      phone: '+44 20 7946 0958',
    });
    http.verify();
  });

  it('rejects combined phone numbers longer than 40 characters', () => {
    const { component, http } = setup();
    component.form.patchValue({
      legalName: 'Long Phone Ltd',
      email: 'long@example.test',
      phoneCountry: 'AS', // +1684 (5 chars + 1 space = 6)
      phone: '1'.repeat(35), // 6 + 35 = 41 > 40
    });
    expect(component.form.controls.phone.hasError('maxlength')).toBe(true);
    component.save();
    http.expectNone('/api/v1/masters/clients/quick-add');
    http.verify();
  });

  it('rejects phone values that contain no digits', () => {
    const { component, http } = setup();
    component.form.patchValue({
      legalName: 'No Digit Phone Ltd',
      email: 'nodigit@example.test',
      phone: '---',
    });
    expect(component.form.controls.phone.hasError('pattern')).toBe(true);
    component.save();
    http.expectNone('/api/v1/masters/clients/quick-add');
    http.verify();
  });
});
