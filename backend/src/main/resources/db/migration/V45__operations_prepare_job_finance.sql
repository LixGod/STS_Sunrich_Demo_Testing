-- The field Operations Manager prepares the job's billing and actual costs.
-- Finance reviews submitted vendor bills and purchase orders; Management reviews invoices and the FDA.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN (
    'FIN.COST.READ', 'FIN.COST.WRITE', 'FIN.INVOICE.READ', 'FIN.INVOICE.WRITE',
    'FIN.FDA.READ', 'FIN.FDA.WRITE'
) WHERE r.code = 'OPERATIONS';

DELETE rp FROM role_permission rp JOIN role r ON r.id = rp.role_id
WHERE r.code = 'FINANCE' AND rp.permission_code IN (
    'FIN.COST.WRITE', 'FIN.INVOICE.WRITE', 'FIN.INVOICE.APPROVE',
    'FIN.FDA.WRITE', 'FIN.FDA.APPROVE'
);

DELETE rp FROM role_permission rp JOIN role r ON r.id = rp.role_id
WHERE r.code = 'MANAGEMENT_APPROVER' AND rp.permission_code = 'FIN.COST.APPROVE';
