import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ifMatch } from '../../../core/api/api.models';
import { ActiveStatusChange, ResourceClient } from '../../../core/api/resource.client';

export type ClientRole = 'OWNER' | 'CHARTERER' | 'TRADER' | 'AGENT' | 'OTHER';
export const CLIENT_ROLES: readonly ClientRole[] = ['OWNER', 'CHARTERER', 'TRADER', 'AGENT', 'OTHER'];

export interface Client {
  id: string;
  code: string;
  legalName: string;
  roles: ClientRole[];
  taxRegistrationNo: string | null;
  billingAddress: string | null;
  countryCode: string | null;
  phone: string | null;
  emails: string[];
  phones: string[];
  defaultCurrency: string | null;
  paymentTerms: string | null;
  active: boolean;
  updatedAt: string | null;
  version: number | null;
}

export type ClientRequest = Omit<Client, 'id' | 'active' | 'updatedAt' | 'version'>;

export interface ClientContact {
  id: string;
  clientId: string;
  fullName: string;
  position: string | null;
  email: string | null;
  phone: string | null;
  primary: boolean;
  active: boolean;
  version: number | null;
}

export type ClientContactRequest = Pick<ClientContact, 'fullName' | 'position' | 'email' | 'phone' | 'primary'>;

@Injectable({ providedIn: 'root' })
export class ClientService extends ResourceClient<Client, ClientRequest> {
  constructor() {
    super(inject(HttpClient), `${inject(API_BASE_URL)}/masters/clients`);
  }

  contacts(clientId: string): Observable<ClientContact[]> {
    return this.http.get<ClientContact[]>(`${this.url}/${clientId}/contacts`);
  }

  addContact(clientId: string, request: ClientContactRequest): Observable<ClientContact> {
    return this.http.post<ClientContact>(`${this.url}/${clientId}/contacts`, request, { context: this.silent });
  }

  updateContact(contact: ClientContact, request: ClientContactRequest): Observable<ClientContact> {
    return this.http.put<ClientContact>(`${this.url}/${contact.clientId}/contacts/${contact.id}`, request, {
      headers: { 'If-Match': ifMatch(contact.version) },
      context: this.silent,
    });
  }

  deleteContact(contact: ClientContact): Observable<void> {
    return this.http.delete<void>(`${this.url}/${contact.clientId}/contacts/${contact.id}`, {
      headers: { 'If-Match': ifMatch(contact.version) },
      context: this.silent,
    });
  }

  changeContactStatus(contact: ClientContact, change: ActiveStatusChange): Observable<ClientContact> {
    return this.http.patch<ClientContact>(`${this.url}/${contact.clientId}/contacts/${contact.id}/status`, change, {
      headers: { 'If-Match': ifMatch(contact.version) },
      context: this.silent,
    });
  }
}
