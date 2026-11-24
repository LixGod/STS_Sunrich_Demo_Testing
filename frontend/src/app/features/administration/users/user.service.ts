import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, Page, PageQuery, SILENT_ERRORS, ifMatch, pageParams } from '../../../core/api/api.models';

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface UserSummary {
  id: string;
  fullName: string | null;
  workEmail: string | null;
  department: string | null;
  position: string | null;
  status: UserStatus;
  lastLoginAt: string | null;
}

export interface UserDetail extends UserSummary {
  externalSubjectId: string | null;
  language: string;
  mfaEnabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  version: number | null;
}

export interface UserCreateRequest {
  externalSubjectId: string | null;
  fullName: string | null;
  workEmail: string | null;
  department: string | null;
  position: string | null;
}

export type UserUpdateRequest = UserCreateRequest;

export interface RoleScope {
  id: string;
  roleId: string;
  roleCode: string;
  roleName: string;
  branchId: string | null;
  validFrom: string;
  validTo: string | null;
}

export interface RoleScopeRequest {
  roleId: string;
  branchId: string | null;
  validTo: string | null;
}

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  permissions: string[];
  version: number;
}

export interface Permission {
  code: string;
  module: string;
  description: string;
}

export interface PermissionOverride { code: string; allowed: boolean; branchIds?: string[] | null; }

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/admin`;
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  list(query: PageQuery): Observable<Page<UserSummary>> {
    return this.http.get<Page<UserSummary>>(`${this.url}/users`, { params: pageParams(query) });
  }

  get(id: string): Observable<UserDetail> {
    return this.http.get<UserDetail>(`${this.url}/users/${id}`);
  }

  create(request: UserCreateRequest): Observable<UserDetail> {
    return this.http.post<UserDetail>(`${this.url}/users`, request, { context: this.silent });
  }

  update(id: string, version: number | null, request: UserUpdateRequest): Observable<UserDetail> {
    return this.http.put<UserDetail>(`${this.url}/users/${id}`, request, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }

  changeStatus(id: string, version: number | null, status: UserStatus, reason: string | null): Observable<UserDetail> {
    return this.http.patch<UserDetail>(`${this.url}/users/${id}/status`, { status, reason }, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }

  setPassword(id: string, password: string): Observable<void> {
    return this.http.put<void>(`${this.url}/users/${id}/password`, { password }, { context: this.silent });
  }

  delete(id: string, version: number | null): Observable<void> {
    return this.http.delete<void>(`${this.url}/users/${id}`, {
      headers: { 'If-Match': ifMatch(version) }, context: this.silent,
    });
  }

  roleScopes(id: string): Observable<RoleScope[]> {
    return this.http.get<RoleScope[]>(`${this.url}/users/${id}/role-scopes`);
  }

  replaceRoleScopes(id: string, grants: RoleScopeRequest[]): Observable<RoleScope[]> {
    return this.http.put<RoleScope[]>(`${this.url}/users/${id}/role-scopes`, { grants }, { context: this.silent });
  }

  permissionOverrides(id: string): Observable<PermissionOverride[]> {
    return this.http.get<PermissionOverride[]>(`${this.url}/users/${id}/permission-overrides`);
  }

  replacePermissionOverrides(id: string, overrides: PermissionOverride[]): Observable<PermissionOverride[]> {
    return this.http.put<PermissionOverride[]>(`${this.url}/users/${id}/permission-overrides`, { overrides }, { context: this.silent });
  }

  roles(): Observable<Role[]> {
    return this.http.get<Role[]>(`${this.url}/roles`);
  }

  createRole(request: Pick<Role, 'code' | 'name' | 'description' | 'permissions'>): Observable<Role> {
    return this.http.post<Role>(`${this.url}/roles`, request, { context: this.silent });
  }

  updateRole(role: Role, request: Pick<Role, 'name' | 'description' | 'permissions'>): Observable<Role> {
    return this.http.put<Role>(`${this.url}/roles/${role.id}`, request, {
      headers: { 'If-Match': ifMatch(role.version) }, context: this.silent,
    });
  }

  permissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.url}/permissions`);
  }
}
