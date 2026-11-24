import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, firstValueFrom, map, of, tap, throwError } from 'rxjs';
import { API_BASE_URL, SILENT_ERRORS, ifMatch } from '../api/api.models';

export interface OwnProfile {
  externalSubjectId: string | null;
  fullName: string | null;
  workEmail: string | null;
  department: string | null;
  position: string | null;
  version: number | null;
}

export interface OwnProfileUpdate {
  username: string;
  fullName: string | null;
  workEmail: string | null;
  department: string | null;
  position: string | null;
}

export interface Grant {
  roleCode: string;
  branchId: string | null;
}

export interface CurrentUser {
  id: string;
  fullName: string;
  workEmail: string | null;
  language: string;
  permissions: string[];
  grants: Grant[];
  directPermissions?: string[];
  globalPermissions?: string[];
}

/**
 * Session state for the signed-in user. The session itself is an HttpOnly cookie managed by the server;
 * nothing secret is stored in the browser. Permissions here only decide what to *show* — the API enforces them.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  private readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  private readonly user = signal<CurrentUser | null>(null);
  private readonly permissionSet = computed(() => new Set(this.user()?.permissions ?? []));

  readonly currentUser = this.user.asReadonly();
  readonly isAuthenticated = computed(() => this.user() !== null);

  /** Called once at start-up; resolves (never rejects) whether or not a session exists. */
  restoreSession(): Promise<void> {
    return firstValueFrom(
      this.http.get<CurrentUser>(`${this.baseUrl}/me`, { context: this.silent }).pipe(
        tap((user) => this.user.set(user)),
        map(() => undefined),
        catchError(() => {
          this.user.set(null);
          return of(undefined);
        }),
      ),
    );
  }

  login(username: string, password: string): Observable<CurrentUser> {
    return this.http
      .post<CurrentUser>(`${this.baseUrl}/auth/login`, { username, password }, { context: this.silent })
      .pipe(tap((user) => this.user.set(user)));
  }

  profile(): Observable<OwnProfile> {
    return this.http.get<OwnProfile>(`${this.baseUrl}/me/profile`);
  }

  updateProfile(version: number | null, details: OwnProfileUpdate): Observable<OwnProfile> {
    return this.http.put<OwnProfile>(`${this.baseUrl}/me/profile`, details, {
      headers: { 'If-Match': ifMatch(version) }, context: this.silent,
    });
  }

  refreshUser(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(tap(user => this.user.set(user)));
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/logout`, null).pipe(
      tap(() => this.clearSession()),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.clearSession();
          return of(undefined);
        }
        return throwError(() => error);
      }),
    );
  }

  /** Forget the local copy of the session (e.g. after the server answered 401). */
  clearSession(): void {
    this.user.set(null);
  }

  hasPermission(permission: string): boolean {
    return this.permissionSet().has(permission);
  }

  hasGlobalPermission(permission: string): boolean {
    return this.user()?.globalPermissions?.includes(permission) ?? false;
  }

  hasAnyPermission(permissions: readonly string[]): boolean {
    return permissions.length === 0 || permissions.some((p) => this.permissionSet().has(p));
  }
}
