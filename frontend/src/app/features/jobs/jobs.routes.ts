import { Routes } from '@angular/router';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** STS jobs (part of navigation group 1, Dashboard & Job 360). Phase 2c: register and overview. */
export const JOB_ROUTES: Routes = [
  {
    path: '',
    canMatch: [permissionGuard(Permissions.jobRead)],
    data: { breadcrumb: 'nav.jobs' },
    children: [
      { path: '', loadComponent: () => import('./job-list.component').then((m) => m.JobListComponent) },
      {
        path: ':id',
        loadComponent: () => import('./job-detail.component').then((m) => m.JobDetailComponent),
        data: { breadcrumb: 'common.details' },
      },
    ],
  },
];
