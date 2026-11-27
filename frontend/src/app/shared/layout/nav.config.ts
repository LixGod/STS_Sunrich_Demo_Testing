import { DOCUMENT_READERS, Permissions } from '../../core/auth/permissions';

export interface NavItem {
  labelKey: string;
  route: string;
  /** Visible if the user holds any of these. */
  permissions: readonly string[];
  globalOnly?: boolean;
}

export interface NavGroup {
  id: string;
  labelKey: string;
  icon: string;
  items: readonly NavItem[];
  sections?: readonly { labelKey: string; items: readonly NavItem[] }[];
  /** Delivery phase for groups not yet built; they show as "coming in phase n". */
  phase?: number;
}

/** Navigation groups; setup and user administration have separate destinations. */
export const NAV_GROUPS: readonly NavGroup[] = [
  {
    id: 'dashboard',
    labelKey: 'nav.dashboard',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'nav.myDashboard', route: '/dashboard', permissions: [] },
      { labelKey: 'nav.jobs', route: '/jobs', permissions: [Permissions.jobRead] },
    ],
  },
  {
    id: 'commercial',
    labelKey: 'nav.commercial',
    icon: 'pi pi-briefcase',
    items: [
      { labelKey: 'nav.enquiries', route: '/commercial/enquiries', permissions: [Permissions.enquiryRead] },
    ],
  },
  {
    id: 'technical',
    labelKey: 'nav.technical',
    icon: 'pi pi-compass',
    items: [{ labelKey: 'nav.vesselParticulars', route: '/technical/vessels', permissions: [Permissions.techVesselRead] }],
  },
  {
    id: 'operations',
    labelKey: 'nav.operations',
    icon: 'pi pi-sync',
    items: [
      { labelKey: 'nav.liveOperations', route: '/operations', permissions: [Permissions.opsLiveRead] },
      { labelKey: 'nav.inventory', route: '/operations/inventory', permissions: [Permissions.masterResourceRead], globalOnly: true },
    ],
  },
  {
    id: 'finance',
    labelKey: 'nav.finance',
    icon: 'pi pi-wallet',
    items: [
      { labelKey: 'nav.invoices', route: '/finance/invoices', permissions: [Permissions.finInvoiceRead] },
      { labelKey: 'nav.receipts', route: '/finance/receipts', permissions: [Permissions.finReceiptRead] },
      { labelKey: 'nav.receivables', route: '/finance/receivables', permissions: [Permissions.finInvoiceRead] },
      { labelKey: 'nav.purchaseOrders', route: '/finance/purchase-orders', permissions: [Permissions.finCostRead] },
      { labelKey: 'nav.vendorBills', route: '/finance/vendor-bills', permissions: [Permissions.finCostRead] },
      { labelKey: 'nav.profitability', route: '/finance/profitability', permissions: [Permissions.finProfitRead] },
    ],
  },
  {
    id: 'reports',
    labelKey: 'nav.reports',
    icon: 'pi pi-file',
    items: [
      { labelKey: 'nav.documentRegister', route: '/reports/documents', permissions: DOCUMENT_READERS },
      { labelKey: 'nav.jobHistory', route: '/reports/job-history', permissions: [Permissions.reportManagementRead] },
    ],
  },
  {
    id: 'sts-admin',
    labelKey: 'nav.stsAdmin',
    icon: 'pi pi-cog',
    items: [],
    sections: [
      { labelKey: 'nav.general', items: [
        { labelKey: 'nav.mailboxConnection', route: '/administration/mailbox', permissions: [Permissions.masterTechnicalWrite], globalOnly: true },
      ] },
      { labelKey: 'nav.organizationMasters', items: [
        { labelKey: 'nav.branches', route: '/administration/branches', permissions: [Permissions.masterOrgRead] },
      ] },
      { labelKey: 'nav.operationsSetup', items: [
        { labelKey: 'nav.operationsLists', route: '/operations/lists', permissions: [Permissions.masterOperationsWrite] },
      ] },
      { labelKey: 'nav.commercialMasters', items: [
        { labelKey: 'nav.clients', route: '/administration/clients', permissions: [Permissions.masterCommercialRead] },
        { labelKey: 'nav.vessels', route: '/administration/vessels', permissions: [Permissions.masterCommercialRead] },
        { labelKey: 'nav.cargoGrades', route: '/administration/cargo-grades', permissions: [Permissions.masterCommercialRead] },
        { labelKey: 'nav.units', route: '/administration/units', permissions: [Permissions.masterCommercialRead] },
        { labelKey: 'nav.operationTypes', route: '/administration/operation-types', permissions: [Permissions.masterCommercialRead] },
      ] },
      { labelKey: 'nav.financeMasters', items: [
        { labelKey: 'nav.tariffs', route: '/administration/tariffs', permissions: [Permissions.masterFinanceRead] },
        { labelKey: 'estimate.branchDefaults', route: '/commercial/branch-cost-defaults', permissions: [Permissions.masterFinanceWrite] },
        { labelKey: 'nav.costHeads', route: '/administration/cost-heads', permissions: [Permissions.masterFinanceRead] },
        { labelKey: 'nav.currencies', route: '/administration/currencies', permissions: [Permissions.masterFinanceRead] },
      ] },
      { labelKey: 'nav.technicalResourceMasters', items: [
        { labelKey: 'nav.documentTypes', route: '/administration/document-types', permissions: [Permissions.masterTechnicalRead] },
        { labelKey: 'nav.documentTemplates', route: '/technical/templates', permissions: [Permissions.masterTechnicalWrite] },
        { labelKey: 'nav.jpoAnnex', route: '/technical/annex', permissions: [Permissions.masterTechnicalRead] },
        { labelKey: 'nav.weatherParameters', route: '/technical/weather-parameters', permissions: [Permissions.masterTechnicalRead] },
        { labelKey: 'resources.admin.equipment', route: '/administration/equipment', permissions: [Permissions.masterResourceRead] },
        { labelKey: 'resources.admin.personnel', route: '/administration/personnel', permissions: [Permissions.masterResourceRead] },
      ] },
    ],
  },
  {
    id: 'super-admin',
    labelKey: 'nav.superAdmin',
    icon: 'pi pi-shield',
    items: [
      { labelKey: 'nav.users', route: '/administration/users', permissions: [Permissions.adminUserRead] },
      { labelKey: 'nav.roles', route: '/administration/roles', permissions: [Permissions.adminRoleRead] },
      { labelKey: 'nav.auditTrail', route: '/administration/audit', permissions: [Permissions.auditRead] },
    ],
  },
];
