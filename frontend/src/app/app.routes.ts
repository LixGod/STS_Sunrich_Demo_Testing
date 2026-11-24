import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { anonymousGuard, authChildGuard, authGuard, globalPermissionGuard, permissionGuard } from './core/guards/auth.guards';
import { Permissions } from './core/auth/permissions';
import { comingSoonGuard } from './shared/components/coming-soon/coming-soon.guard';

export const routes: Routes = [
  {
    path: 'login',
    canMatch: [anonymousGuard],
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
    title: 'Ship2Ship · Sign in',
  },
  {
    path: '',
    canMatch: [authGuard],
    canActivateChild: [authChildGuard],
    loadComponent: () => import('./shared/layout/app-shell/app-shell.component').then((m) => m.AppShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'profile', loadComponent: () => import('./features/profile/profile.component').then(m => m.ProfileComponent),
        data: { breadcrumb: 'users.myProfile' } },
      { path: 'inbox', canMatch: [permissionGuard(Permissions.jobRead)],
        loadComponent: () => import('./shared/components/action-inbox/action-inbox.component').then((m) => m.ActionInboxComponent) },
      {
        path: 'dashboard',
        loadChildren: () => import('./features/dashboard/dashboard.routes').then((m) => m.DASHBOARD_ROUTES),
      },
      {
        path: 'jobs/:jobId/weather',
        canMatch: [permissionGuard(Permissions.techWeatherRead)],
        loadComponent: () => import('./features/technical/weather-page.component').then((m) => m.WeatherPageComponent),
      },
      {
        path: 'jobs/:jobId/jpo',
        canMatch: [permissionGuard(Permissions.techJpoRead)],
        loadComponent: () => import('./features/technical/jpo-page.component').then((m) => m.JpoPageComponent),
      },
      {
        path: 'jobs/:jobId/mooring-plan',
        canMatch: [permissionGuard(Permissions.techMooringRead)],
        loadComponent: () => import('./features/technical/mooring-plan-page.component').then((m) => m.MooringPlanPageComponent),
      },
      {
        path: 'jobs/:jobId/resources',
        canMatch: [permissionGuard(Permissions.opsAllocationRead)],
        loadComponent: () => import('./features/resources/job-resources-page.component').then((m) => m.JobResourcesPageComponent),
      },
      {
        path: 'jobs/:jobId/operations',
        canMatch: [permissionGuard(Permissions.opsLiveRead)],
        loadComponent: () => import('./features/operations/operations-console.component').then((m) => m.OperationsConsoleComponent),
      },
      {
        path: 'jobs/:jobId/movements',
        canMatch: [permissionGuard(Permissions.opsMovementRead)],
        loadComponent: () => import('./features/movements/movements-page.component').then((m) => m.MovementsPageComponent),
      },
      {
        path: 'jobs/:jobId/recovery',
        canMatch: [permissionGuard(Permissions.opsRecoveryRead)],
        loadComponent: () => import('./features/movements/recovery-page.component').then((m) => m.RecoveryPageComponent),
      },
      {
        path: 'operations/inventory',
        canMatch: [globalPermissionGuard(Permissions.masterResourceRead)],
        loadComponent: () => import('./features/resources/inventory-page.component').then((m) => m.InventoryPageComponent),
        data: { breadcrumb: 'nav.inventory' },
      },
      {
        path: 'operations',
        loadChildren: () => import('./features/operations/operations.routes').then((m) => m.OPERATIONS_ROUTES),
      },
      {
        path: 'jobs/:jobId/final-report',
        canMatch: [permissionGuard(Permissions.reportFinalRead)],
        loadComponent: () => import('./features/finalreport/final-report-page.component').then((m) => m.FinalReportPageComponent),
      },
      {
        path: 'jobs/:jobId/finance',
        canMatch: [permissionGuard(Permissions.finCostRead, Permissions.finFdaRead, Permissions.finInvoiceRead)],
        loadComponent: () => import('./features/finance/job-finance.component').then((m) => m.JobFinanceComponent),
      },
      {
        path: 'jobs/:jobId/invoices',
        redirectTo: ({ params }) => inject(Router).createUrlTree(['/jobs', params['jobId'], 'finance'], { queryParams: { tab: 'invoices' } }),
      },
      {
        path: 'finance',
        loadChildren: () => import('./features/finance/finance.routes').then((m) => m.FINANCE_ROUTES),
      },
      {
        path: 'reports',
        loadChildren: () => import('./features/reports/reports.routes').then((m) => m.REPORTS_ROUTES),
      },
      {
        path: 'jobs/:jobId/technical',
        canMatch: [permissionGuard(Permissions.techJobRead)],
        loadComponent: () => import('./features/technical/job-technical.component').then((m) => m.JobTechnicalComponent),
      },
      {
        path: 'jobs',
        loadChildren: () => import('./features/jobs/jobs.routes').then((m) => m.JOB_ROUTES),
      },
      {
        path: 'commercial',
        loadChildren: () => import('./features/commercial/commercial.routes').then((m) => m.COMMERCIAL_ROUTES),
      },
      {
        path: 'technical',
        loadChildren: () => import('./features/technical/technical.routes').then((m) => m.TECHNICAL_ROUTES),
      },
      {
        path: 'administration',
        loadChildren: () => import('./features/administration/administration.routes').then((m) => m.ADMINISTRATION_ROUTES),
      },
      {
        path: 'coming-soon/:group',
        canActivate: [comingSoonGuard],
        loadComponent: () =>
          import('./shared/components/coming-soon/coming-soon.component').then((m) => m.ComingSoonComponent),
        data: { breadcrumb: 'nav.comingSoon' },
      },
      {
        path: 'access-denied',
        loadComponent: () =>
          import('./shared/components/access-denied/access-denied.component').then((m) => m.AccessDeniedComponent),
        data: { breadcrumb: 'errors.accessDenied' },
      },
      { path: '**', redirectTo: 'dashboard' },
    ],
  },
  // Anonymous visitor on an unknown deep link: the shell route's guard redirects to login.
  { path: '**', redirectTo: '' },
];
