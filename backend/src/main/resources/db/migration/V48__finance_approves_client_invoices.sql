-- Operations prepares client invoices; Finance approves them after submission.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT id, 'FIN.INVOICE.APPROVE' FROM role WHERE code = 'FINANCE';

DELETE rp FROM role_permission rp JOIN role r ON r.id = rp.role_id
WHERE r.code = 'MANAGEMENT_APPROVER' AND rp.permission_code = 'FIN.INVOICE.APPROVE';
