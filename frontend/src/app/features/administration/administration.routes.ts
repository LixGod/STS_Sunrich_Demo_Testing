import { Route, Router, Routes } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';
import { MASTER_LISTS } from './master-list/master-list.config';

const stsAdminPermission = (permission: string) => [
  () => {
    const auth = inject(AuthService);
    return auth.currentUser()?.directPermissions?.includes(permission) ||
      auth.hasGlobalPermission(permission.replace(/\.READ$/, '.WRITE')) ||
      auth.currentUser()?.grants.some(grant => grant.roleCode === 'STS_ADMIN' || grant.roleCode === 'SYSTEM_ADMIN')
      ? true : inject(Router).createUrlTree(['/access-denied']);
  },
  permissionGuard(permission),
];

/** A flat master list served by the shared configurable list page. */
function masterList(path: string, configId: keyof typeof MASTER_LISTS, permission: string): Route {
  const config = MASTER_LISTS[configId];
  return {
    path,
    canMatch: stsAdminPermission(permission),
    loadComponent: () => import('./master-list/master-list-page.component').then((m) => m.MasterListPageComponent),
    data: { breadcrumb: config.titleKey, config },
  };
}

/** Administration & Masters (navigation group 7). Each screen is its own lazily loaded chunk. */
export const ADMINISTRATION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'branches' },
  {
    path: 'users',
    canMatch: [permissionGuard(Permissions.adminUserRead)],
    data: { breadcrumb: 'nav.users' },
    children: [
      { path: '', loadComponent: () => import('./users/user-list.component').then((m) => m.UserListComponent) },
      {
        path: 'new',
        canMatch: [permissionGuard(Permissions.adminUserWrite)],
        loadComponent: () => import('./users/user-form.component').then((m) => m.UserFormComponent),
        data: { breadcrumb: 'common.new' },
      },
      {
        path: ':id',
        loadComponent: () => import('./users/user-form.component').then((m) => m.UserFormComponent),
        data: { breadcrumb: 'common.details' },
      },
    ],
  },
  {
    path: 'roles',
    canMatch: [permissionGuard(Permissions.adminRoleRead)],
    loadComponent: () => import('./roles/role-list.component').then((m) => m.RoleListComponent),
    data: { breadcrumb: 'nav.roles' },
  },
  {
    path: 'branches',
    canMatch: stsAdminPermission(Permissions.masterOrgRead),
    data: { breadcrumb: 'nav.branches' },
    children: [
      { path: '', loadComponent: () => import('./branches/branch-list.component').then((m) => m.BranchListComponent) },
      {
        path: 'new',
        canMatch: [permissionGuard(Permissions.masterOrgWrite)],
        loadComponent: () => import('./branches/branch-form.component').then((m) => m.BranchFormComponent),
        data: { breadcrumb: 'common.new' },
      },
      {
        path: ':id',
        loadComponent: () => import('./branches/branch-form.component').then((m) => m.BranchFormComponent),
        data: { breadcrumb: 'common.details' },
      },
    ],
  },
  {
    path: 'clients',
    canMatch: stsAdminPermission(Permissions.masterCommercialRead),
    data: { breadcrumb: 'nav.clients' },
    children: [
      { path: '', loadComponent: () => import('./clients/client-list.component').then((m) => m.ClientListComponent) },
      {
        path: 'new',
        canMatch: [permissionGuard(Permissions.masterCommercialWrite)],
        loadComponent: () => import('./clients/client-form.component').then((m) => m.ClientFormComponent),
        data: { breadcrumb: 'common.new' },
      },
      {
        path: ':id',
        loadComponent: () => import('./clients/client-form.component').then((m) => m.ClientFormComponent),
        data: { breadcrumb: 'common.details' },
      },
    ],
  },
  masterList('vessels', 'vessels', Permissions.masterCommercialRead),
  masterList('cargo-grades', 'cargoGrades', Permissions.masterCommercialRead),
  masterList('operation-types', 'operationTypes', Permissions.masterCommercialRead),
  masterList('units', 'units', Permissions.masterCommercialRead),
  masterList('tariffs', 'tariffs', Permissions.masterFinanceRead),
  masterList('cost-heads', 'costHeads', Permissions.masterFinanceRead),
  masterList('currencies', 'currencies', Permissions.masterFinanceRead),
  masterList('document-types', 'documentTypes', Permissions.masterTechnicalRead),
  ...(['equipment', 'personnel'] as const).map(resourceMode => ({
    path: resourceMode,
    canMatch: stsAdminPermission(Permissions.masterResourceRead),
    loadComponent: () => import('../resources/resource-admin-page.component').then(m => m.ResourceAdminPageComponent),
    data: { breadcrumb: `resources.admin.${resourceMode}`, resourceMode },
  })),
  {
    path: 'audit',
    canMatch: [permissionGuard(Permissions.auditRead)],
    loadComponent: () => import('./audit/audit-page.component').then((m) => m.AuditPageComponent),
    data: { breadcrumb: 'nav.auditTrail' },
  },
];
