import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { ClientFormComponent } from './client-form.component';
import { Client, ClientRequest, ClientService } from './client.service';

describe('ClientFormComponent client communication details', () => {
  it('loads legacy phone and saves ordered client emails and phones', () => {
    const client = {
      id: 'client-1', code: 'CL-1', legalName: 'Fleet Client', roles: ['CHARTERER'], taxRegistrationNo: null,
      billingAddress: null, countryCode: 'IN', phone: '+91 9000000000', defaultCurrency: null,
      paymentTerms: null, active: true, updatedAt: null, version: 3,
      emails: ['ops@fleet.test'], phones: ['+91 9000000000'],
    } as Client;
    let sent: ClientRequest | null = null;
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideRouter([{ path: '**', children: [] }]), provideTranslateService(), MessageService, ConfirmationService,
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => null } },
      { provide: LookupService, useValue: { currencies: () => of([]) } },
      { provide: ClientService, useValue: {
        get: () => of(client), contacts: () => of([]),
        update: (_id: string, _version: number, request: ClientRequest) => { sent = request; return of(client); },
      } },
    ] });
    const fixture = TestBed.createComponent(ClientFormComponent);
    fixture.componentRef.setInput('id', client.id);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component['form'].controls.emails.value).toEqual(['ops@fleet.test']);
    expect(component['form'].controls.phones.value).toEqual(['+91 9000000000']);
    component['addEmail']();
    component['form'].controls.emails.at(1).setValue('billing@fleet.test');
    component['addPhone']();
    component['form'].controls.phones.at(1).setValue('+91 9111111111');
    component['save']();
    const saved = sent as ClientRequest | null;
    expect(saved?.emails).toEqual(['ops@fleet.test', 'billing@fleet.test']);
    expect(saved?.phones).toEqual(['+91 9000000000', '+91 9111111111']);
    expect(saved?.phone).toBe('+91 9000000000');
  });

  it('starts a new client with one email and one phone field, validates digits, and drops blank rows on save', () => {
    let created: ClientRequest | null = null;
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideRouter([{ path: '**', children: [] }]), provideTranslateService(), MessageService, ConfirmationService,
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => null } },
      { provide: LookupService, useValue: { currencies: () => of([]) } },
      { provide: ClientService, useValue: {
        create: (request: ClientRequest) => {
          created = request;
          return of({ ...request, id: 'client-new', active: true, updatedAt: null, version: 0 });
        },
      } },
    ] });
    const fixture = TestBed.createComponent(ClientFormComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component['form'].controls.emails.length).toBe(1);
    expect(component['form'].controls.phones.length).toBe(1);

    component['form'].patchValue({ code: 'cl-99', legalName: 'Multi Channel Co', roles: ['CHARTERER'] });
    component['form'].controls.phones.at(0).setValue('---');
    expect(component['form'].controls.phones.at(0).invalid).toBe(true);

    component['form'].controls.emails.at(0).setValue('one@multi.test');
    component['addEmail']();
    component['addEmail']();
    component['form'].controls.emails.at(2).setValue('two@multi.test');
    component['form'].controls.phones.at(0).setValue('+91 9000000001');
    component['addPhone']();
    component['form'].controls.phones.at(1).setValue('+91 9000000002');
    component['save']();

    expect(created).not.toBeNull();
    expect(created!.emails).toEqual(['one@multi.test', 'two@multi.test']);
    expect(created!.phones).toEqual(['+91 9000000001', '+91 9000000002']);
    expect(created!.phone).toBe('+91 9000000001');
  });
});
