import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { Subject, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { BranchService } from '../organisation.service';
import { UserFormComponent } from './user-form.component';
import { PermissionOverride, RoleScope, UserDetail, UserService } from './user.service';

describe('UserFormComponent Module Access Checkboxes', () => {
  it('accepts an empty account form as a draft', () => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(), MessageService,
      { provide: BranchService, useValue: { options: () => of([]) } },
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => ({ id: 'admin' }) } },
      { provide: UserService, useValue: { roles: () => of([]), permissions: () => of([]) } },
    ] });
    TestBed.overrideComponent(UserFormComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(UserFormComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance['form'].valid).toBe(true);
  });

  it('toggles permission checkboxes and module-level select all', () => {
    const mockUserService = {
      roles: () => of([
        { id: 'role-1', code: 'OPERATIONS', name: 'Operations', description: null, permissions: ['JOB.READ', 'JOB.WRITE'] },
      ]),
      permissions: () => of([
        { code: 'JOB.READ', module: 'JOBS', description: 'View STS jobs' },
        { code: 'JOB.WRITE', module: 'JOBS', description: 'Advance jobs' },
        { code: 'JOB.CLOSE.APPROVE', module: 'JOBS', description: 'Approve closure' },
      ]),
      get: () => of({
        id: 'user-1', externalSubjectId: 'ops-1', fullName: 'Ops User', workEmail: 'ops@sts.com',
        department: null, position: null, status: 'ACTIVE', language: 'en', mfaEnabled: false,
        lastLoginAt: null, createdAt: '2026-01-01', version: 1,
      }),
      roleScopes: () => of([{ id: 'grant-1', roleId: 'role-1', roleCode: 'OPERATIONS', roleName: 'Operations', branchId: null, branchName: null, validTo: null }]),
      permissionOverrides: () => of([]),
    };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService(),
        MessageService,
        { provide: UserService, useValue: mockUserService },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (p: string) => p === Permissions.adminUserWrite,
            currentUser: () => ({ id: 'admin-99', grants: [{ roleCode: 'SYSTEM_ADMIN', branchId: null }] }),
          },
        },
      ],
    });

    const fixture = TestBed.createComponent(UserFormComponent);
    fixture.componentRef.setInput('id', 'user-1');
    fixture.detectChanges();

    const comp = fixture.componentInstance;

    // A role alone does not select module access.
    expect(comp['isPermissionGranted']('JOB.READ')).toBe(false);
    expect(comp['isPermissionGranted']('JOB.WRITE')).toBe(false);
    expect(comp['isPermissionGranted']('JOB.CLOSE.APPROVE')).toBe(false);

    // Checking 'JOB.CLOSE.APPROVE' grants it
    comp['togglePermission']('JOB.CLOSE.APPROVE', { target: { checked: true } } as unknown as Event);
    expect(comp['isPermissionGranted']('JOB.CLOSE.APPROVE')).toBe(true);
    expect(comp['overrides']()['JOB.CLOSE.APPROVE']).toBe(true);

    // Clicking the rendered checkbox selects the group without toggling its details panel.
    const group = comp['permissionGroups']()[0];
    const selectAll = fixture.nativeElement.querySelector('.group-toggle-wrap input') as HTMLInputElement;
    selectAll.click();
    fixture.detectChanges();
    expect(selectAll.checked).toBe(true);
    expect((selectAll.closest('details') as HTMLDetailsElement).open).toBe(true);
    expect(comp['isGroupAllChecked'](group)).toBe(true);
    expect(comp['isPermissionGranted']('JOB.READ')).toBe(true);
    expect(comp['isPermissionGranted']('JOB.WRITE')).toBe(true);
    expect(comp['isPermissionGranted']('JOB.CLOSE.APPROVE')).toBe(true);

    comp['showPermissions']([]);
    comp['showGrants']([{ id: 'expired', roleId: 'role-1', roleCode: 'OPERATIONS', roleName: 'Operations',
      branchId: null, validFrom: '2026-01-01', validTo: '2020-01-01' }]);
    expect(comp['isPermissionGranted']('JOB.READ')).toBe(false);
    expect(comp['isPermissionGranted']('JOB.WRITE')).toBe(false);
    comp['showPermissions']([{ code: 'JOB.READ', allowed: true, branchIds: ['branch-1'] }]);
    expect(comp['isPermissionGranted']('JOB.READ')).toBe(true);
    expect(comp['isBranchScoped']('JOB.READ')).toBe(true);
  });

  it('ignores a previous user response after switching users', () => {
    const first = new Subject<UserDetail>();
    const second = new Subject<UserDetail>();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(), MessageService,
      { provide: BranchService, useValue: { options: () => of([]) } },
      { provide: AuthService, useValue: { hasPermission: () => false, currentUser: () => null } },
      { provide: UserService, useValue: {
        roles: () => of([]), permissions: () => of([]),
        get: (id: string) => id === 'first' ? first : second,
        roleScopes: () => of([]), permissionOverrides: () => of([]),
      } },
    ] });
    const fixture = TestBed.createComponent(UserFormComponent);
    fixture.componentRef.setInput('id', 'first');
    fixture.detectChanges();
    fixture.componentRef.setInput('id', 'second');
    fixture.detectChanges();

    const user = (id: string): UserDetail => ({ id, externalSubjectId: id, fullName: id, workEmail: `${id}@example.com`,
      department: null, position: null, status: 'ACTIVE', language: 'en', mfaEnabled: false,
      lastLoginAt: null, createdAt: null, updatedAt: null, version: 1 });
    first.next(user('first'));
    second.next(user('second'));
    expect(fixture.componentInstance['record']()?.id).toBe('second');
    expect(fixture.componentInstance['form'].controls.fullName.value).toBe('second');
  });

  it('saves manual permissions while preserving existing branch scope', () => {
    const save = vi.fn(() => of([]));
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(), MessageService,
      { provide: BranchService, useValue: { options: () => of([]) } },
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => ({ id: 'admin', grants: [{ roleCode: 'SYSTEM_ADMIN', branchId: null }] }) } },
      { provide: UserService, useValue: {
        roles: () => of([]), permissions: () => of([]),
        get: () => of({ id: 'user-1', fullName: 'User', workEmail: 'user@example.com', status: 'ACTIVE', language: 'en', version: 1 }),
        roleScopes: () => of([{ id: 'g1', roleId: 'r1', roleCode: 'OPS', roleName: 'Operations', branchId: 'branch-1', validTo: null }]),
        permissionOverrides: () => of([{ code: 'JOB.READ', allowed: true, branchIds: ['branch-1'] }]), replacePermissionOverrides: save,
      } },
    ] });
    TestBed.overrideComponent(UserFormComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(UserFormComponent);
    fixture.componentRef.setInput('id', 'user-1');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp['savePermissions']();
    expect(save).toHaveBeenCalledOnce();
    expect(save).toHaveBeenCalledWith('user-1', [{ code: 'JOB.READ', allowed: true, branchIds: ['branch-1'] }]);
  });

  it('ignores access saves completed after switching users', () => {
    const permissionSave = new Subject<PermissionOverride[]>();
    const grantSave = new Subject<RoleScope[]>();
    const user = (id: string): UserDetail => ({ id, externalSubjectId: id, fullName: id, workEmail: `${id}@example.com`,
      department: null, position: null, status: 'ACTIVE', language: 'en', mfaEnabled: false,
      lastLoginAt: null, createdAt: null, updatedAt: null, version: 1 });
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTranslateService(), MessageService,
      { provide: BranchService, useValue: { options: () => of([]) } },
      { provide: AuthService, useValue: { hasPermission: () => true, currentUser: () => ({ id: 'admin', grants: [{ roleCode: 'SYSTEM_ADMIN', branchId: null }] }) } },
      { provide: UserService, useValue: {
        roles: () => of([]), permissions: () => of([]), get: (id: string) => of(user(id)),
        roleScopes: () => of([]), permissionOverrides: () => of([]),
        replaceRoleScopes: () => grantSave, replacePermissionOverrides: () => permissionSave,
      } },
    ] });
    TestBed.overrideComponent(UserFormComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(UserFormComponent);
    fixture.componentRef.setInput('id', 'first');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp['saveGrants']();
    comp['savePermissions']();
    fixture.componentRef.setInput('id', 'second');
    fixture.detectChanges();

    grantSave.next([{ id: 'old', roleId: 'role-1', roleCode: 'OPERATIONS', roleName: 'Operations',
      branchId: null, validFrom: '2026-01-01', validTo: null }]);
    permissionSave.next([{ code: 'JOB.READ', allowed: true }]);
    expect(comp['record']()?.id).toBe('second');
    expect(comp['grantsHistory']()).toEqual([]);
    expect(comp['overrides']()).toEqual({});
    expect(comp['savingGrants']()).toBe(false);
    expect(comp['savingPermissions']()).toBe(false);
  });
});
