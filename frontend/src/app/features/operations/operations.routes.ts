import { Routes } from '@angular/router';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** Operations & Cargo (navigation group 4). Increment 4b: live operations list and the operations lists. */
export const OPERATIONS_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    canMatch: [permissionGuard(Permissions.opsLiveRead)],
    loadComponent: () => import('./operations-jobs.component').then((m) => m.OperationsJobsComponent),
  },
  {
    path: 'lists',
    canMatch: [permissionGuard(Permissions.masterOperationsWrite)],
    loadComponent: () => import('./operation-lists.component').then((m) => m.OperationListsComponent),
    data: { breadcrumb: 'nav.operationsLists' },
  },
];
