import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { EnquiryFormComponent } from './enquiry-form.component';

describe('EnquiryFormComponent client autofill', () => {
  it('keeps country, phone and contact from quick-add even if reload fails, and shows multiple client phones', () => {
    let failDetails = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideRouter([]),
        provideTranslateService(),
        MessageService,
        { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => null } },
        {
          provide: LookupService,
          useValue: {
            branches: () => of([]),
            cargoGrades: () => of([]),
            units: () => of([]),
            operationTypes: () => of([]),
            clientDetails: () => (failDetails
              ? throwError(() => new Error('forbidden'))
              : of({ countryCode: 'AE', phone: '+971 50 111 2222', phones: ['+971 50 111 2222', '+971 50 333 4444'] })),
            contacts: () => of([{ value: 'ct-2', label: 'Primary <p@gulf.test>', meta: { primary: 'true' } }]),
            searchClients: () => of([]),
            searchVessels: () => of([]),
          },
        },
      ],
    });

    const fixture = TestBed.createComponent(EnquiryFormComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component['clientAdded']({
      client: { id: 'c-1', legalName: 'Demo Client 1', code: 'CL-1', countryCode: 'IN', phone: '+91 9876543210' },
      contact: { id: 'ct-1', email: 'client1@clientcompany.com' },
    });
    expect(component['clientCountry']()).toBe('India');
    expect(component['clientPhone']()).toBe('+91 9876543210');
    expect(component['contactOptions']()).toEqual([
      { value: 'ct-1', label: 'Demo Client 1 <client1@clientcompany.com>' },
      { value: 'ct-2', label: 'Primary <p@gulf.test>', meta: { primary: 'true' } },
    ]);

    failDetails = false;
    component['form'].controls.client.setValue({ id: 'c-2', label: 'Gulf Client (CL-2)' });
    expect(component['clientCountry']()).toBe('United Arab Emirates');
    expect(component['clientPhone']()).toBe('+971 50 111 2222, +971 50 333 4444');
    expect(component['form'].controls.clientContactId.value).toBe('ct-2');
  });
});
