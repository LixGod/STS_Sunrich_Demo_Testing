import { Routes } from '@angular/router';

export const DASHBOARD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./dashboard.component').then((m) => m.DashboardComponent),
    data: { breadcrumb: 'nav.dashboard' },
    title: 'Ship2Ship · Dashboard',
  },
];
