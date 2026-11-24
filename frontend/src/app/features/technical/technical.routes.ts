import { Routes } from '@angular/router';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** Vessels & Technical Planning (navigation group 3). Increment 3a: vessel documents and verified particulars. */
export const TECHNICAL_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'vessels' },
  {
    path: 'templates',
    canMatch: [permissionGuard(Permissions.masterTechnicalWrite)],
    loadComponent: () => import('./technical-templates.component').then(m => m.TechnicalTemplatesComponent),
    data: { breadcrumb: 'Document templates' },
  },
  {
    path: 'annex',
    canMatch: [permissionGuard(Permissions.masterTechnicalRead)],
    loadComponent: () => import('./annex-templates.component').then(m => m.AnnexTemplatesComponent),
    data: { breadcrumb: 'nav.jpoAnnex' },
  },
  {
    path: 'weather-parameters',
    canMatch: [permissionGuard(Permissions.masterTechnicalRead)],
    loadComponent: () => import('./weather-parameters.component').then(m => m.WeatherParametersComponent),
    data: { breadcrumb: 'nav.weatherParameters' },
  },
  {
    path: 'vessels',
    canMatch: [permissionGuard(Permissions.techVesselRead)],
    data: { breadcrumb: 'nav.vesselParticulars' },
    children: [
      { path: '', loadComponent: () => import('./vessel-list.component').then((m) => m.VesselListComponent) },
      {
        path: ':id',
        loadComponent: () => import('./vessel-technical.component').then((m) => m.VesselTechnicalComponent),
        data: { breadcrumb: 'common.details' },
      },
    ],
  },
];
