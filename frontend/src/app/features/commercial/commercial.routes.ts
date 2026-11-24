import { Routes } from '@angular/router';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** Commercial & Enquiries (navigation group 2): enquiries with their estimate and quotation (acceptance creates the job). */
export const COMMERCIAL_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'enquiries' },
  {
    path: 'branch-cost-defaults',
    canMatch: [permissionGuard(Permissions.masterFinanceWrite)],
    loadComponent: () => import('./estimates/branch-cost-defaults.component').then(m => m.BranchCostDefaultsComponent),
    data: { breadcrumb: 'estimate.branchDefaults' },
  },
  {
    path: 'enquiries',
    canMatch: [permissionGuard(Permissions.enquiryRead)],
    data: { breadcrumb: 'nav.enquiries' },
    children: [
      { path: '', loadComponent: () => import('./enquiries/enquiry-list.component').then((m) => m.EnquiryListComponent) },
      {
        path: 'new',
        canMatch: [permissionGuard(Permissions.enquiryWrite)],
        loadComponent: () => import('./enquiries/enquiry-form.component').then((m) => m.EnquiryFormComponent),
        data: { breadcrumb: 'common.new' },
      },
      {
        path: 'drafts/:draftId',
        canMatch: [permissionGuard(Permissions.enquiryWrite)],
        loadComponent: () => import('./enquiries/enquiry-form.component').then((m) => m.EnquiryFormComponent),
        data: { breadcrumb: 'enquiry.draft' },
      },
      {
        path: ':id',
        loadComponent: () => import('./enquiries/enquiry-form.component').then((m) => m.EnquiryFormComponent),
        data: { breadcrumb: 'common.details' },
      },
      {
        path: ':id/estimate',
        canMatch: [permissionGuard(Permissions.estimateRead, Permissions.quotationRead)],
        loadComponent: () => import('./enquiries/estimate-quotation-page.component').then((m) => m.EstimateQuotationPageComponent),
        data: { breadcrumb: 'enquiry.tabs.estimate' },
      },
      {
        path: ':id/quotation',
        redirectTo: ({ params }) => `/commercial/enquiries/${params['id']}/estimate#quotation`,
      },
    ],
  },
];
