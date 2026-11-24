import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { DatePicker } from 'primeng/datepicker';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Subscription, catchError, map, of } from 'rxjs';
import { problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { applyServerErrors } from '../../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../../shared/pipes/utc-datetime.pipe';
import { BranchService } from '../organisation.service';
import { Permission, PermissionOverride, RoleScope, UserDetail, UserService, UserStatus } from './user.service';

type GrantForm = FormGroup<{
  roleId: FormControl<string | null>;
  branchId: FormControl<string | null>;
  validTo: FormControl<Date | null>;
}>;

/**
 * User account (FR-UM-01/03) and scoped role grants (D01). Administrators cannot change their own status
 * or grants; the server enforces the same rule.
 */
@Component({
  selector: 's2s-user-form',
  imports: [
    ReactiveFormsModule, TranslatePipe, Button, DatePicker, InputText, Message, Select, UtcDateTimePipe,
    FormFieldComponent, PageHeaderComponent, StatusTagComponent, ReasonDialogComponent, AuditTrailComponent,
  ],
  templateUrl: './user-form.component.html',
  styleUrl: './user-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserFormComponent {
  private readonly destroyRef = inject(DestroyRef);
  private loadRequests = new Subscription();
  private loadedId = '';
  private recordVersion = 0;
  private readonly service = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly id = input<string | undefined>(undefined);

  protected readonly record = signal<UserDetail | null>(null);
  protected readonly grantsHistory = signal<RoleScope[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly savingPassword = signal(false);
  protected readonly deleting = signal(false);
  protected readonly passwordError = signal<string | null>(null);
  protected readonly savingGrants = signal(false);
  protected readonly savingPermissions = signal(false);
  protected readonly permissionsDirty = signal(false);
  protected readonly permissionErrorKey = signal<string | null>(null);
  protected readonly overrides = signal<Record<string, boolean>>({});
  protected readonly permissionBranches = signal<Record<string, string[] | null>>({});
  protected readonly errorKey = signal<string | null>(null);
  protected readonly grantsErrorKey = signal<string | null>(null);
  protected readonly pendingStatus = signal<UserStatus | null>(null);

  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.adminUserWrite));
  protected readonly canReadAudit = computed(() => this.auth.hasPermission(Permissions.auditRead));
  protected readonly isSelf = computed(() => this.record()?.id === this.auth.currentUser()?.id);
  protected readonly canManageAccess = computed(() => this.canWrite() && !!this.record() && !this.isSelf());
  protected readonly canManageModuleAccess = computed(() => this.canManageAccess() &&
    !!this.auth.currentUser()?.grants.some(grant => grant.roleCode === 'SYSTEM_ADMIN'));
  protected readonly statusDialogOpen = computed(() => this.pendingStatus() !== null);

  protected readonly allRoles = toSignal(this.service.roles().pipe(catchError(() => of([]))), { initialValue: [] });
  protected readonly roleOptions = computed(() => this.allRoles().map((r) => ({ label: r.name, value: r.id })));
  protected readonly branchOptions = toSignal(inject(BranchService).options().pipe(catchError(() => of([]))), { initialValue: [] });

  protected readonly permissionGroups = toSignal(this.service.permissions().pipe(
    map(permissions => {
      const groups = new Map<string, Permission[]>();
      for (const permission of permissions.filter(p => !p.code.startsWith('ADMIN.') && p.code !== Permissions.auditRead)) {
        const items = groups.get(permission.module) ?? [];
        items.push(permission);
        groups.set(permission.module, items);
      }
      return Array.from(groups, ([name, items]) => ({ name, items }));
    }),
    catchError(() => of([])),
  ), { initialValue: [] });

  protected readonly form = this.fb.group({
    externalSubjectId: ['', [Validators.maxLength(128)]],
    fullName: ['', [Validators.maxLength(150)]],
    workEmail: ['', [Validators.email, Validators.maxLength(254)]],
    department: ['', [Validators.maxLength(80)]],
    position: ['', [Validators.maxLength(80)]],
  });

  protected readonly passwordForm = this.fb.group({ password: [''], confirmPassword: [''] });

  protected readonly grants = new FormArray<GrantForm>([]);

  protected setPassword(): void {
    const user = this.record();
    if (!user || !this.canManageModuleAccess() || this.savingPassword()) return;
    const { password, confirmPassword } = this.passwordForm.getRawValue();
    if (password !== confirmPassword || new TextEncoder().encode(password).length < 8 ||
        new TextEncoder().encode(password).length > 72) {
      this.passwordError.set('Password must be 8 to 72 bytes and both entries must match.');
      return;
    }
    this.passwordError.set(null);
    this.savingPassword.set(true);
    this.service.setPassword(user.id, password).subscribe({
      next: () => {
        this.savingPassword.set(false);
        this.passwordForm.reset();
        this.messages.add({ severity: 'success', summary: 'Password saved' });
      },
      error: (error: unknown) => {
        this.savingPassword.set(false);
        this.passwordError.set(toProblem(error).detail || 'Could not save password.');
      },
    });
  }

  protected deleteUser(): void {
    const user = this.record();
    if (!user || !this.canManageModuleAccess() || this.deleting() ||
        !window.confirm(`Delete ${user.fullName || user.externalSubjectId || 'this user'}? Their access will end immediately. This cannot be undone.`)) return;
    this.deleting.set(true);
    this.service.delete(user.id, user.version).subscribe({
      next: () => {
        this.messages.add({ severity: 'success', summary: 'User deleted' });
        void this.router.navigate(['/administration/users']);
      },
      error: (error: unknown) => {
        this.deleting.set(false);
        this.errorKey.set(problemKey(toProblem(error)));
      },
    });
  }

  constructor() {
    this.destroyRef.onDestroy(() => this.loadRequests.unsubscribe());
    effect(() => {
      const id = this.id();
      untracked(() => (id ? this.load(id) : this.applyAccess()));
    });
  }

  protected addGrant(): void {
    this.grants.push(this.grantForm(null));
  }

  protected removeGrant(index: number): void {
    this.grants.removeAt(index);
    this.grants.markAsDirty();
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const details = {
      externalSubjectId: value.externalSubjectId.trim() || null,
      fullName: value.fullName.trim() || null,
      workEmail: value.workEmail.trim() || null,
      department: value.department.trim() || null,
      position: value.position.trim() || null,
    };
    const current = this.record();
    const version = this.recordVersion;
    this.saving.set(true);
    this.errorKey.set(null);
    const call = current
      ? this.service.update(current.id, current.version, details)
      : this.service.create(details);
    call.subscribe({
      next: (saved) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
        if (!current) {
          void this.router.navigate(['/administration/users', saved.id], { replaceUrl: true });
        } else {
          this.show(saved);
        }
      },
      error: (error: unknown) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        const problem = toProblem(error);
        if (!applyServerErrors(this.form, problem)) this.errorKey.set(problemKey(problem));
      },
    });
  }

  protected saveGrants(): void {
    const current = this.record();
    if (!current || this.grants.invalid || this.savingGrants()) {
      this.grants.markAllAsTouched();
      return;
    }
    const requests = this.grants.getRawValue().map((g) => ({
      roleId: g.roleId!,
      branchId: g.branchId,
      validTo: g.validTo ? toIsoDate(g.validTo) : null,
    }));
    const version = this.recordVersion;
    this.savingGrants.set(true);
    this.grantsErrorKey.set(null);
    this.service.replaceRoleScopes(current.id, requests).subscribe({
      next: (scopes) => {
        if (version !== this.recordVersion) return;
        this.savingGrants.set(false);
        this.showGrants(scopes);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
      },
      error: (error: unknown) => {
        if (version !== this.recordVersion) return;
        this.savingGrants.set(false);
        this.grantsErrorKey.set(problemKey(toProblem(error)));
      },
    });
  }

  protected isPermissionGranted(code: string): boolean {
    return this.overrides()[code] === true;
  }

  protected isBranchScoped(code: string): boolean {
    return !!this.permissionBranches()[code]?.length;
  }

  protected togglePermission(code: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = { ...this.overrides() };
    const branches = { ...this.permissionBranches() };
    if (checked) { next[code] = true; branches[code] = null; }
    else { delete next[code]; delete branches[code]; }
    this.overrides.set(next);
    this.permissionBranches.set(branches);
    this.permissionsDirty.set(true);
  }

  protected isGroupAllChecked(group: { items: Permission[] }): boolean {
    return group.items.length > 0 && group.items.every((p) => this.isPermissionGranted(p.code));
  }

  protected isGroupIndeterminate(group: { items: Permission[] }): boolean {
    const count = group.items.filter((p) => this.isPermissionGranted(p.code)).length;
    return count > 0 && count < group.items.length;
  }

  protected groupActiveCount(group: { items: Permission[] }): number {
    return group.items.filter((p) => this.isPermissionGranted(p.code)).length;
  }

  protected toggleGroup(group: { items: Permission[] }, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = { ...this.overrides() };
    const branches = { ...this.permissionBranches() };
    for (const permission of group.items) {
      const code = permission.code;
      if (checked && !next[code]) { next[code] = true; branches[code] = null; }
      if (!checked) { delete next[code]; delete branches[code]; }
    }
    this.overrides.set(next);
    this.permissionBranches.set(branches);
    this.permissionsDirty.set(true);
  }

  protected savePermissions(): void {
    const current = this.record();
    if (!current || !this.canManageModuleAccess() || this.savingPermissions()) return;
    const version = this.recordVersion;
    this.savingPermissions.set(true);
    this.permissionErrorKey.set(null);
    const overrides: PermissionOverride[] = Object.keys(this.overrides()).map(code =>
      ({ code, allowed: true, branchIds: this.permissionBranches()[code] ?? null }));
    this.service.replacePermissionOverrides(current.id, overrides).subscribe({
      next: saved => {
        if (version !== this.recordVersion) return;
        this.savingPermissions.set(false);
        this.showPermissions(saved);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
      },
      error: error => {
        if (version !== this.recordVersion) return;
        this.savingPermissions.set(false);
        this.permissionErrorKey.set(problemKey(toProblem(error)));
      },
    });
  }

  protected confirmStatus(reason: string | null): void {
    const status = this.pendingStatus();
    if (status) this.changeStatus(status, reason);
  }

  protected changeStatus(status: UserStatus, reason: string | null): void {
    const current = this.record();
    if (!current || this.saving()) return;
    const version = this.recordVersion;
    this.saving.set(true);
    this.service.changeStatus(current.id, current.version, status, reason).subscribe({
      next: (saved) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        this.pendingStatus.set(null);
        this.show(saved);
      },
      error: (error: unknown) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        this.pendingStatus.set(null);
        this.errorKey.set(problemKey(toProblem(error)));
      },
    });
  }

  protected setStatusDialog(open: boolean): void {
    if (!open) this.pendingStatus.set(null);
  }

  protected reload(): void {
    const id = this.id();
    if (id) this.load(id);
  }

  private load(id: string): void {
    this.loadRequests.unsubscribe();
    this.loadRequests = new Subscription();
    if (this.loadedId !== id) {
      this.loadedId = id;
      this.recordVersion++;
      this.record.set(null);
      this.grantsHistory.set([]);
      this.overrides.set({});
      this.permissionBranches.set({});
      this.permissionsDirty.set(false);
      this.saving.set(false);
      this.savingGrants.set(false);
      this.savingPermissions.set(false);
      this.pendingStatus.set(null);
      this.grants.clear();
      this.form.reset();
    }
    this.loading.set(true);
    this.errorKey.set(null);
    this.loadRequests.add(this.service.get(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (user) => {
        this.loading.set(false);
        this.show(user);
      },
      error: () => {
        this.loading.set(false);
        this.errorKey.set('errors.loadFailed');
      },
    }));
    this.loadRequests.add(this.service.roleScopes(id).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (scopes) => this.showGrants(scopes), error: () => undefined }));
    this.loadRequests.add(this.service.permissionOverrides(id).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: overrides => this.showPermissions(overrides), error: () => undefined }));
  }

  private show(user: UserDetail): void {
    this.record.set(user);
    this.form.reset({
      externalSubjectId: user.externalSubjectId ?? '',
      fullName: user.fullName ?? '',
      workEmail: user.workEmail ?? '',
      department: user.department ?? '',
      position: user.position ?? '',
    });
    this.applyAccess();
  }

  private showGrants(scopes: RoleScope[]): void {
    this.grantsHistory.set(scopes);
    const today = toIsoDate(new Date());
    this.grants.clear();
    scopes
      .filter((s) => s.validTo == null || s.validTo >= today)
      .forEach((s) => this.grants.push(this.grantForm(s)));
    this.grants.markAsPristine();
    if (!this.canManageAccess()) this.grants.disable();
  }

  private showPermissions(overrides: PermissionOverride[]): void {
    this.overrides.set(Object.fromEntries(overrides.map(({ code, allowed }) => [code, allowed])));
    this.permissionBranches.set(Object.fromEntries(overrides.map(({ code, branchIds }) => [code, branchIds ?? null])));
    this.permissionsDirty.set(false);
  }

  private grantForm(scope: RoleScope | null): GrantForm {
    const group: GrantForm = new FormGroup({
      roleId: new FormControl<string | null>(scope?.roleId ?? null, Validators.required),
      branchId: new FormControl<string | null>(scope?.branchId ?? null),
      validTo: new FormControl<Date | null>(scope?.validTo ? new Date(`${scope.validTo}T00:00:00`) : null),
    });
    return group;
  }

  /** The authentication subject is fixed once set; drafts can add one later. */
  private applyAccess(): void {
    if (!this.canWrite()) {
      this.form.disable();
    } else if (this.record()?.externalSubjectId) {
      this.form.controls.externalSubjectId.disable();
    }
  }
}

function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}
