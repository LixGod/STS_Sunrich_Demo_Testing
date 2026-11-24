-- Baseline roles from department decision D01 and the Phase 1 permission catalogue.
-- The role-to-permission mapping is a starting configuration for Ship2Ship to confirm (SRS §27).

INSERT INTO permission (code, module, description) VALUES
    ('ADMIN.USER.READ',  'ADMINISTRATION', 'View users and their role assignments'),
    ('ADMIN.USER.WRITE', 'ADMINISTRATION', 'Create and update users, status and role assignments'),
    ('ADMIN.ROLE.READ',  'ADMINISTRATION', 'View roles and permissions'),
    ('MASTER.ORG.READ',  'MASTERS',        'View legal entities, branches and STS locations'),
    ('MASTER.ORG.WRITE', 'MASTERS',        'Create and update legal entities, branches and STS locations'),
    ('AUDIT.READ',       'AUDIT',          'View audit trail records within scope');

INSERT INTO role (id, code, name, description) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000001'), 'SYSTEM_ADMIN',        'System Administrator',            'Users, roles, configuration, technical monitoring. No automatic commercial, marine or finance sign-off.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000002'), 'MANAGEMENT_APPROVER', 'Management Approver',             'Dashboards, quotations, exceptions and closure within configured authority limits.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000003'), 'COMMERCIAL',          'Commercial Executive / Manager',  'Enquiries, costing, quotations and client notices.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000004'), 'OPERATIONS',          'Operations Coordinator / Manager','Jobs, ETA, deployment, SOF and logistics.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000005'), 'TECHNICAL',           'Technical Officer / Approver',    'Vessel review, JPO, mooring plans and checklists.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000006'), 'HSE_REVIEWER',        'HSE / Incident Reviewer',         'Incidents, corrective actions and safety records.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000007'), 'FINANCE',             'Finance Executive / Approver',    'Costs, vendor bills, invoices, receipts and FDA.'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000008'), 'AUDITOR',             'Read-Only Auditor',               'Auditable read-only records within assigned scope.');

-- System Administrator: all Phase 1 permissions.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r CROSS JOIN permission p WHERE r.code = 'SYSTEM_ADMIN';

-- Every business role can read the organisation masters it is scoped to.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'MASTER.ORG.READ' FROM role r WHERE r.code <> 'SYSTEM_ADMIN';

-- Management and Auditor can read the audit trail; Auditor can also review users and roles.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'AUDIT.READ' FROM role r WHERE r.code IN ('MANAGEMENT_APPROVER', 'AUDITOR');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('ADMIN.USER.READ', 'ADMIN.ROLE.READ')
WHERE r.code = 'AUDITOR';
