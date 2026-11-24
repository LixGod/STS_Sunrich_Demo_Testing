import { Routes } from '@angular/router';
import { DOCUMENT_READERS, Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** Reports & History (navigation group 6): document register and job history. */
export const REPORTS_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'documents' },
  {
    path: 'documents',
    canMatch: [permissionGuard(...DOCUMENT_READERS)],
    loadComponent: () => import('./document-register.component').then((m) => m.DocumentRegisterComponent),
    data: { breadcrumb: 'nav.documentRegister' },
  },
  {
    path: 'job-history',
    canMatch: [permissionGuard(Permissions.reportManagementRead)],
    loadComponent: () => import('./job-history.component').then((m) => m.JobHistoryComponent),
    data: { breadcrumb: 'nav.jobHistory' },
  },
];
