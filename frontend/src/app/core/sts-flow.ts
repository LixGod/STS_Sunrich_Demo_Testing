/** The Ship2Ship STS flow, shared by the job page and the dashboard. Steps 11 and 12 close the job after the ten operating steps. */
export const FLOW_STEPS = ['Enquiry', 'Costing and estimate', 'Acceptance from owners', 'Documents from owners',
  'JPO, certificates and weather', 'Proposed mooring plan', 'Check ETA of both vessels', 'STS operation', 'Incidents',
  'Equipment and team back to shore', 'STS operation report', 'Final disbursement account'] as const;

/** The step a job is on, judged from its status alone (the job page refines it from each step's own record). */
export const STATUS_STEP: Record<string, number> = { OPEN: 4, DOCUMENTS_PENDING: 4, PLANNING: 5, APPROVED_FOR_READINESS: 7,
  MOBILISING: 8, IN_PROGRESS: 8, DEMOBILISING: 10, REPORT_PENDING: 11, FINANCIAL_CLOSING: 12, CLOSED: 13 };
