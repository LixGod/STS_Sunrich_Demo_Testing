import { Routes } from '@angular/router';
import { Permissions } from '../../core/auth/permissions';
import { permissionGuard } from '../../core/guards/auth.guards';

/** Finance & FDA (navigation group 5). Phase 5: invoices, receipts, receivables, purchase orders, vendor bills and profitability (job costs and the FDA are on the job page). */
export const FINANCE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'invoices' },
  {
    path: 'invoices',
    canMatch: [permissionGuard(Permissions.finInvoiceRead)],
    loadComponent: () => import('./invoice-list.component').then((m) => m.InvoiceListComponent),
    data: { breadcrumb: 'nav.invoices' },
  },
  {
    path: 'invoices/:id',
    canMatch: [permissionGuard(Permissions.finInvoiceRead)],
    loadComponent: () => import('./invoice-page.component').then((m) => m.InvoicePageComponent),
    data: { breadcrumb: 'nav.invoices' },
  },
  {
    path: 'receipts',
    canMatch: [permissionGuard(Permissions.finReceiptRead)],
    loadComponent: () => import('./receipts.component').then((m) => m.ReceiptsComponent),
    data: { breadcrumb: 'nav.receipts' },
  },
  {
    path: 'receivables',
    canMatch: [permissionGuard(Permissions.finInvoiceRead)],
    loadComponent: () => import('./receivables.component').then((m) => m.ReceivablesComponent),
    data: { breadcrumb: 'nav.receivables' },
  },
  {
    path: 'purchase-orders',
    canMatch: [permissionGuard(Permissions.finCostRead)],
    loadComponent: () => import('./cost-documents.component').then((m) => m.CostDocumentsComponent),
    data: { breadcrumb: 'nav.purchaseOrders', kind: 'PO' },
  },
  {
    path: 'vendor-bills',
    canMatch: [permissionGuard(Permissions.finCostRead)],
    loadComponent: () => import('./cost-documents.component').then((m) => m.CostDocumentsComponent),
    data: { breadcrumb: 'nav.vendorBills', kind: 'BILL' },
  },
  {
    path: 'profitability',
    canMatch: [permissionGuard(Permissions.finProfitRead)],
    loadComponent: () => import('./profitability.component').then((m) => m.ProfitabilityComponent),
    data: { breadcrumb: 'nav.profitability' },
  },
];
